package dev.magelite.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Json;
import org.apache.log4j.Logger;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Online-Konto der Desktop-App (lokale Engine mit {@code --account}): einzige Quelle der fly-Session. Electron meldet
 * das Cookie {@code ml_sess} per {@code POST /api/account/sync}; angemeldet ({@link State#OK}) zeigt die lokale App die
 * Decks, den Helden und die Statistik des Kontos ({@link AccountProxy}), Solo-Spiele laufen weiter hier und werden
 * dem Konto gemeldet ({@link AccountReporter}). {@link State#OFFLINE}: bewusst ohne Konto, lokale Daten wie bisher.
 * <p>
 * Ohne {@code --account} ({@link State#DISABLED}: Dev-Engine, Spikes, e2e-relay) bleibt alles wie vor dem Konto-Modus.
 */
public final class AccountLink {

    private static final Logger LOG = Logger.getLogger(AccountLink.class);
    /** Konto pruefen: fly kann gerade kalt starten (7-17 s) */
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(25);

    public enum State {
        /** ohne {@code --account}: lokale Daten, kein Gate */
        DISABLED,
        /** Electron hat noch nichts gemeldet bzw. die Session wird bei fly geprueft */
        CHECKING,
        /** keine Session: Login zeigen */
        NONE,
        /** Session ungueltig (abgemeldet, Admin hat beendet): Login zeigen */
        EXPIRED,
        /** fly nicht erreichbar: erneut versuchen oder offline */
        UNREACHABLE,
        /** oeffentliches Konto, Monatsbudget aufgebraucht: nur offline */
        LIMITED,
        /** angemeldet: Kontodaten von fly */
        OK,
        /** bewusst offline: lokale Daten */
        OFFLINE
    }

    public record Account(long id, String name, String tier) {
    }

    /** Fuer {@code /api/me} ({@code account}) und {@code /api/account}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(State state, String server, Account user, String error, Long resetsAt) {
    }

    private final boolean enabled;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "account-link");
        t.setDaemon(true);
        return t;
    });
    private final List<Consumer<AccountLink>> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile State state;
    private volatile String server;
    private volatile String session;
    private volatile Account user;
    private volatile String error;
    private volatile Long resetsAt;
    /** zaehlt jede neue Pruefung; eine veraltete Antwort ueberschreibt nichts */
    private long generation;

    public AccountLink(boolean enabled) {
        this.enabled = enabled;
        this.state = enabled ? State.CHECKING : State.DISABLED;
        if (enabled) {
            // meldet Electron nichts (z.B. Engine ohne App gestartet), nicht ewig "pruefen": Login zeigen
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (lock) {
                    if (state == State.CHECKING && server == null) {
                        set(State.NONE, null);
                    }
                }
            }, "account-sync-wait");
            t.setDaemon(true);
            t.start();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Bei jeder Zustandsaenderung (auf dem Worker bzw. dem aufrufenden Thread); kurz halten. */
    public void onChange(Consumer<AccountLink> l) {
        listeners.add(l);
    }

    public State state() {
        return state;
    }

    public String server() {
        return server;
    }

    public String session() {
        return session;
    }

    /** Konto, solange angemeldet ({@link State#OK}), sonst null. */
    public Account user() {
        return state == State.OK ? user : null;
    }

    /** Konto der letzten erfolgreichen Pruefung (auch wenn jetzt offline/abgelaufen) - fuer die Warteschlange. */
    public Account lastUser() {
        return user;
    }

    public Status status() {
        Account u = user;
        State s = state;
        boolean showUser = s == State.OK || s == State.LIMITED || s == State.OFFLINE || s == State.UNREACHABLE;
        return new Status(s, server, showUser ? u : null, error, s == State.LIMITED ? resetsAt : null);
    }

    /** Kontodaten von fly? */
    public boolean active() {
        return state == State.OK;
    }

    /** Lokale Daten (ohne Konto-Modus oder bewusst offline)? */
    public boolean local() {
        State s = state;
        return s == State.DISABLED || s == State.OFFLINE;
    }

    /**
     * Electron meldet die Session (beim Start, nach Login/Abmelden, nach einem Engine-Neustart).
     *
     * @param session null = nicht angemeldet
     * @param offline Nutzer hat "Offline spielen" gewaehlt
     */
    public void sync(String serverUrl, String session, boolean offline) {
        if (!enabled) {
            throw new IllegalStateException("Konto-Modus ist aus (--account fehlt)");
        }
        String base = ServerCalls.normalizeServer(serverUrl);
        String sess = session == null || session.isBlank() ? null : session.strip();
        synchronized (lock) {
            boolean changed = !base.equals(this.server) || !java.util.Objects.equals(sess, this.session);
            this.server = base;
            this.session = sess;
            if (offline) {
                set(State.OFFLINE, null);
                return;
            }
            if (sess == null) {
                set(State.NONE, null);
                return;
            }
            if (!changed && (state == State.OK || state == State.CHECKING || state == State.LIMITED)) {
                return; // gleiche Session (z.B. Seite neu geladen): nicht erneut pruefen
            }
            check();
        }
    }

    /** Erneut pruefen (Gate "Erneut versuchen", Konto-Button nach Offline). */
    public void retry() {
        if (!enabled) {
            return;
        }
        synchronized (lock) {
            if (session == null) {
                set(State.NONE, null);
            } else {
                check();
            }
        }
    }

    /** "Offline spielen": lokale Daten, Session bleibt fuer spaeter gemerkt. */
    public void goOffline() {
        if (!enabled) {
            return;
        }
        synchronized (lock) {
            generation++;
            set(State.OFFLINE, null);
        }
    }

    /** fly hat die Session abgelehnt (401 beim Durchreichen). */
    public void expired() {
        synchronized (lock) {
            if (state == State.OK) {
                generation++;
                set(State.EXPIRED, "Anmeldung abgelaufen – bitte neu anmelden");
            }
        }
    }

    /** Monatsbudget erschoepft (503 budget beim Durchreichen). */
    public void limited(Long resets) {
        synchronized (lock) {
            if (state == State.OK) {
                generation++;
                this.resetsAt = resets;
                set(State.LIMITED, null);
            }
        }
    }

    private void check() {
        long gen = ++generation;
        String srv = server;
        String sess = session;
        set(State.CHECKING, null);
        worker.execute(() -> validate(gen, srv, sess));
    }

    /** {@code GET /api/me} mit der Session (passiv: Pruefen allein haelt fly nicht wach). */
    private void validate(long gen, String srv, String sess) {
        State next;
        String err = null;
        Account acc = null;
        Long resets = null;
        try {
            HttpResponse<String> res = ServerCalls.json(srv, sess, "GET", "/api/me", null, true, CHECK_TIMEOUT);
            if (res.statusCode() == 200) {
                JsonNode me = Json.MAPPER.readTree(res.body());
                JsonNode u = me.path("user");
                if (!"server".equals(me.path("mode").asText()) || u.path("id").asLong() <= 0) {
                    next = State.UNREACHABLE;
                    err = "Unerwartete Antwort vom Server";
                } else {
                    acc = new Account(u.path("id").asLong(), u.path("name").asText(""), u.path("tier").asText("friend"));
                    boolean limited = me.path("budget").path("limited").asBoolean(false);
                    if (limited) {
                        resets = me.path("budget").hasNonNull("resetsAt") ? me.path("budget").path("resetsAt").asLong() : null;
                    }
                    next = limited ? State.LIMITED : State.OK;
                }
            } else if (res.statusCode() == 401) {
                next = State.EXPIRED;
            } else {
                next = State.UNREACHABLE;
                err = "Server antwortet mit HTTP " + res.statusCode();
            }
        } catch (Exception e) {
            next = State.UNREACHABLE;
            // "nicht erreichbar" sagt die UI selbst; hier nur Details (Timeout, DNS ...), falls es welche gibt
            err = e.getMessage() == null || e.getMessage().isBlank() ? null : "Details: " + e.getMessage();
        }
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            if (acc != null) {
                user = acc;
            }
            resetsAt = resets;
            set(next, err);
        }
        LOG.info("Konto " + srv + ": " + next + (acc == null ? "" : " (" + acc.name() + ", " + acc.id() + ")") + (err == null ? "" : " - " + err));
    }

    private void set(State s, String err) {
        State before = this.state;
        this.state = s;
        this.error = err;
        if (before != s || s == State.OK) {
            for (Consumer<AccountLink> l : listeners) {
                try {
                    l.accept(this);
                } catch (RuntimeException e) {
                    LOG.warn("Konto-Listener: " + e, e);
                }
            }
        }
    }

    /** JSON-Aufruf am Server mit der Session. @throws java.io.IOException nicht erreichbar */
    public HttpResponse<String> call(String method, String path, String jsonBody, boolean passive, Duration timeout)
            throws java.io.IOException {
        String srv = server;
        String sess = session;
        if (srv == null || sess == null) {
            throw new java.io.IOException("nicht angemeldet");
        }
        return ServerCalls.json(srv, sess, method, path, jsonBody, passive, timeout);
    }
}
