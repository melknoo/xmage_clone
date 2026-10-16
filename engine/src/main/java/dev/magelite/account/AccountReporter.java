package dev.magelite.account;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Json;
import dev.magelite.game.GameHost;
import dev.magelite.stats.GameRecorder;
import dev.magelite.stats.StatsSink;
import org.apache.log4j.Logger;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Solo-Spiele der Desktop-App im Konto-Modus: Das Spiel laeuft lokal (Sitz = Nutzer 1), das Ergebnis bucht der
 * Online-Server ({@code POST /api/games/report}) aufs Konto, mit dem das Spiel gestartet wurde. In die lokale DB kommt
 * nichts. Ist der Server weg (oder inzwischen ein anderes Konto angemeldet), wartet der Bericht in der Warteschlange
 * ({@link AccountStore}) und geht raus, sobald dasselbe Konto wieder angemeldet ist.
 */
public final class AccountReporter {

    private static final Logger LOG = Logger.getLogger(AccountReporter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(25);
    private static final String QUEUE = "outbox|";

    private enum Outcome { BOOKED, DONE, RETRY }

    private record Sent(Outcome outcome, JsonNode reward) {
    }

    private final AccountLink link;
    private final AccountStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "account-report");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean flushing = new AtomicBoolean();

    public AccountReporter(AccountLink link, AccountStore store) {
        this.link = link;
        this.store = store;
        // Konto (wieder) angemeldet: liegengebliebene Berichte nachholen
        link.onChange(l -> {
            if (l.state() == AccountLink.State.OK) {
                worker.execute(this::flush);
            }
        });
    }

    /** Reward-Hook fuer ein Spiel, das mit diesem Konto gestartet wurde. */
    public GameHost.RewardHook hookFor(String server, AccountLink.Account account) {
        return (host, seat, over) -> {
            GameRecorder.GameResult result;
            try {
                result = GameRecorder.resultOf(host, over).onlySeat(GameRecorder.seatResult(host, seat));
            } finally {
                StatsSink.unregister(host.getId());
            }
            String json = Json.write(result);
            return new GameHost.PendingReward(CompletableFuture.supplyAsync(() -> deliver(server, account.id(), result.gameId(), json), worker));
        };
    }

    /** @return Belohnung (JSON), null (schon verbucht/abgelehnt) oder {@link GameHost#REWARD_QUEUED} */
    private Object deliver(String server, long accountId, String gameId, String json) {
        Sent s = sameAccount(server, accountId) ? send(json) : new Sent(Outcome.RETRY, null);
        switch (s.outcome()) {
            case BOOKED -> {
                worker.execute(this::flush);
                return s.reward();
            }
            case DONE -> {
                return null;
            }
            default -> {
                store.put(AccountStore.scope(server, accountId) + QUEUE + gameId, json);
                LOG.info("Spielbericht " + gameId + " wartet (Konto " + accountId + ")");
                return GameHost.REWARD_QUEUED;
            }
        }
    }

    private boolean sameAccount(String server, long accountId) {
        AccountLink.Account u = link.user();
        return u != null && u.id() == accountId && server.equals(link.server());
    }

    /** Ein Bericht an den Server. */
    private Sent send(String json) {
        HttpResponse<String> res;
        try {
            res = link.call("POST", "/api/games/report", "{\"result\":" + json + "}", true, TIMEOUT);
        } catch (Exception e) {
            LOG.warn("Spielbericht: Server nicht erreichbar (" + e.getMessage() + ")");
            return new Sent(Outcome.RETRY, null);
        }
        int st = res.statusCode();
        JsonNode body = null;
        try {
            body = Json.MAPPER.readTree(res.body());
        } catch (Exception ignored) {
            // HTML vom fly-Proxy o.ae.
        }
        if (st == 200 && body != null) {
            if (body.path("duplicate").asBoolean(false)) {
                return new Sent(Outcome.DONE, null);
            }
            return new Sent(Outcome.BOOKED, body.get("reward"));
        }
        if (st == 401) {
            link.expired();
        } else if (st == 503 && body != null && body.path("budget").asBoolean(false)) {
            link.limited(null);
        } else if (st == 400) {
            // kaputter Bericht: nicht endlos wiederholen
            LOG.warn("Spielbericht abgelehnt: " + (body == null ? res.body() : body.path("error").asText()));
            return new Sent(Outcome.DONE, null);
        }
        LOG.warn("Spielbericht: HTTP " + st);
        return new Sent(Outcome.RETRY, null);
    }

    /** Warteschlange des angemeldeten Kontos abarbeiten (Worker-Thread); stoppt beim ersten Fehlschlag. */
    private void flush() {
        AccountLink.Account u = link.user();
        String server = link.server();
        if (u == null || server == null || !flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            String prefix = AccountStore.scope(server, u.id()) + QUEUE;
            Map<String, String> todo = store.withPrefix(prefix);
            int sent = 0;
            for (Map.Entry<String, String> e : todo.entrySet()) {
                if (!sameAccount(server, u.id())) {
                    break;
                }
                Sent s = send(e.getValue());
                if (s.outcome() == Outcome.RETRY) {
                    break;
                }
                store.delete(e.getKey());
                sent++;
            }
            if (sent > 0) {
                LOG.info("Nachgereichte Spielberichte: " + sent + "/" + todo.size());
            }
        } finally {
            flushing.set(false);
        }
    }

    /** Wartende Berichte des Kontos (Anzeige). */
    public int queued(String server, long accountId) {
        return store.withPrefix(AccountStore.scope(server, accountId) + QUEUE).size();
    }
}
