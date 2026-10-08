package dev.magelite.auth;

import dev.magelite.admin.UptimeBudget;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selbstregistrierung per E-Mail (Server-Modus). Kostenbremsen: Schalter {@code MAGELITE_SIGNUP}, Captcha, hoechstens
 * {@code perIp} Konten pro IP und 24 h, {@code perDay} insgesamt pro 24 h, {@code maxPublic} oeffentliche Konten, keine
 * Registrierung bei erschoepftem Budget. Zaehler liegen in der DB, weil die Maschine oft stoppt.
 * <p>
 * Neue Konten sind {@link User#PUBLIC} und bis zur Bestaetigung gesperrt; unbestaetigte werden nach
 * {@code unverifiedTtlMs} geloescht. Antworten verraten nie, ob eine E-Mail schon registriert ist.
 */
public final class SignupService {

    private static final Logger LOG = Logger.getLogger(SignupService.class);
    public static final String VERIFY = "verify";
    public static final String RESET = "reset";
    private static final long DAY_MS = 24 * 3600_000L;
    private static final long VERIFY_TTL_MS = DAY_MS;
    private static final long RESET_TTL_MS = 3600_000L;
    /** hoechstens eine Mail je Konto und Zweck in diesem Abstand */
    private static final long MAIL_GAP_MS = 5 * 60_000L;

    /**
     * @param open            Schalter {@code MAGELITE_SIGNUP=open}
     * @param publicUrl       Basis fuer Links in Mails (nie aus dem Host-Header)
     * @param turnstileSiteKey fuer das Widget in der UI (null = kein Captcha, nur Dev)
     */
    public record Settings(boolean open, int maxPublic, int perDay, int perIp, String publicUrl, String turnstileSiteKey,
                           long unverifiedTtlMs) {
    }

    /** Zustand der Registrierung fuer die Startseite. */
    public enum State { OPEN, CLOSED, FULL, DAILY, BUDGET }

    /** Fachliche Ablehnung mit HTTP-Status (403/429/409). */
    public static final class Rejected extends RuntimeException {
        public final int status;

        public Rejected(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final AccountService accounts;
    private final Mailer mailer;
    private final Turnstile turnstile;
    private final Settings settings;
    private final UptimeBudget budget;
    /** "schon registriert"-Hinweise je E-Mail drosseln (kein Token dafuer) */
    private final Map<String, Long> noticeSent = new ConcurrentHashMap<>();

    /** @param mailer null = kein Versand moeglich -> Registrierung geschlossen */
    public SignupService(AccountService accounts, Mailer mailer, Turnstile turnstile, Settings settings, UptimeBudget budget) {
        this.accounts = accounts;
        this.mailer = mailer;
        this.turnstile = turnstile;
        this.settings = settings;
        this.budget = budget;
    }

    public Settings settings() {
        return settings;
    }

    public boolean mailAvailable() {
        return mailer != null && settings.publicUrl() != null;
    }

    public State state() {
        if (!settings.open() || !mailAvailable()) {
            return State.CLOSED;
        }
        if (budget != null && budget.exhausted()) {
            return State.BUDGET;
        }
        if (accounts.countPublic(0, null) >= settings.maxPublic()) {
            return State.FULL;
        }
        if (accounts.countPublic(System.currentTimeMillis() - DAY_MS, null) >= settings.perDay()) {
            return State.DAILY;
        }
        return State.OPEN;
    }

    private static String stateText(State s) {
        return switch (s) {
            case CLOSED -> "Die Registrierung ist geschlossen";
            case FULL -> "Alle Plätze sind vergeben";
            case DAILY -> "Für heute sind alle Plätze vergeben, bitte morgen nochmal versuchen";
            case BUDGET -> "Das Server-Kontingent für diesen Monat ist aufgebraucht";
            case OPEN -> "";
        };
    }

    private void requireCaptcha(String captcha, String ip) {
        if (!turnstile.verify(captcha, ip)) {
            throw new Rejected(400, "Captcha fehlgeschlagen, bitte nochmal versuchen");
        }
    }

    /** Neues Konto anlegen und Bestaetigungsmail senden. Ist die E-Mail vergeben, geht stattdessen ein Hinweis raus. */
    public void signup(String name, String email, char[] password, String captcha, String ip) {
        State st = state();
        if (st != State.OPEN) {
            throw new Rejected(403, stateText(st));
        }
        requireCaptcha(captcha, ip);
        if (accounts.countPublic(System.currentTimeMillis() - DAY_MS, ip) >= settings.perIp()) {
            throw new Rejected(429, "Von diesem Anschluss wurden heute schon genug Konten angelegt");
        }
        Optional<AccountService.EmailAccount> existing = accounts.byEmail(email);
        if (existing.isPresent()) {
            notifyExisting(existing.get(), email);
            return;
        }
        if (accounts.nameTaken(name)) {
            throw new Rejected(409, "Dieser Name ist schon vergeben");
        }
        long id;
        try {
            id = accounts.createPublic(name, email, Passwords.hash(password), ip);
        } catch (AccountService.Conflict e) {
            accounts.byEmail(email).ifPresent(a -> notifyExisting(a, email)); // gleichzeitige Registrierung
            return;
        }
        try {
            sendVerify(id, name, email);
        } catch (RuntimeException e) {
            accounts.delete(id); // ohne Mail koennte das Konto nie bestaetigt werden
            throw e;
        }
    }

    private void notifyExisting(AccountService.EmailAccount a, String email) {
        if (User.PUBLIC.equals(a.tier()) && !a.verified()) {
            if (System.currentTimeMillis() - accounts.lastEmailTokenAt(a.id(), VERIFY) >= MAIL_GAP_MS) {
                sendVerify(a.id(), a.name(), email);
            }
            return;
        }
        long now = System.currentTimeMillis();
        Long last = noticeSent.get(email);
        if (last != null && now - last < MAIL_GAP_MS) {
            return;
        }
        noticeSent.put(email, now);
        mailer.send(email, "MageLite: Du hast schon ein Konto",
                "Hallo " + a.name() + ",\n\n"
                        + "jemand (vermutlich du) wollte sich mit dieser E-Mail-Adresse neu bei MageLite registrieren. "
                        + "Du hast aber schon ein Konto. Passwort vergessen? Auf der Startseite unter \"Passwort vergessen?\" "
                        + "kannst du es zurücksetzen:\n" + settings.publicUrl() + "/\n\n"
                        + "Wenn du das nicht warst, kannst du diese Mail ignorieren.\n");
    }

    private void sendVerify(long id, String name, String email) {
        String token = accounts.createEmailToken(id, VERIFY, VERIFY_TTL_MS);
        mailer.send(email, "MageLite: E-Mail bestätigen",
                "Hallo " + name + ",\n\n"
                        + "bitte bestätige deine E-Mail-Adresse, damit dein MageLite-Konto aktiv wird:\n"
                        + settings.publicUrl() + "/#verify=" + token + "\n\n"
                        + "Der Link gilt 24 Stunden. Wenn du dich nicht registriert hast, ignoriere diese Mail - "
                        + "das Konto wird dann automatisch gelöscht.\n");
        LOG.info("Bestaetigungsmail an Konto #" + id);
    }

    /** Bestaetigungslink einloesen. @return Konto-id */
    public long verify(String token) {
        long id = accounts.consumeEmailToken(VERIFY, token)
                .orElseThrow(() -> new Rejected(400, "Der Link ist ungültig oder abgelaufen"));
        accounts.markVerified(id);
        LOG.info("Konto #" + id + " bestaetigt");
        return id;
    }

    /** Bestaetigungsmail erneut senden (gedrosselt); Antwort immer gleich. */
    public void resend(String email, String captcha, String ip) {
        if (!mailAvailable()) {
            throw new Rejected(403, stateText(State.CLOSED));
        }
        requireCaptcha(captcha, ip);
        accounts.byEmail(email)
                .filter(a -> User.PUBLIC.equals(a.tier()) && !a.verified())
                .filter(a -> System.currentTimeMillis() - accounts.lastEmailTokenAt(a.id(), VERIFY) >= MAIL_GAP_MS)
                .ifPresent(a -> sendVerify(a.id(), a.name(), email));
    }

    /** "Passwort vergessen": Reset-Link fuer Konten mit Passwort (gedrosselt); Antwort immer gleich. */
    public void forgot(String email, String captcha, String ip) {
        if (!mailAvailable()) {
            throw new Rejected(403, "Passwort zurücksetzen ist auf diesem Server nicht eingerichtet");
        }
        requireCaptcha(captcha, ip);
        accounts.byEmail(email)
                .filter(AccountService.EmailAccount::hasPassword)
                .filter(a -> System.currentTimeMillis() - accounts.lastEmailTokenAt(a.id(), RESET) >= MAIL_GAP_MS)
                .ifPresent(a -> {
                    String token = accounts.createEmailToken(a.id(), RESET, RESET_TTL_MS);
                    mailer.send(email, "MageLite: Passwort zurücksetzen",
                            "Hallo " + a.name() + ",\n\n"
                                    + "hier kannst du ein neues Passwort für MageLite setzen:\n"
                                    + settings.publicUrl() + "/#reset=" + token + "\n\n"
                                    + "Der Link gilt eine Stunde. Wenn du das nicht angefordert hast, ignoriere diese Mail.\n");
                    LOG.info("Reset-Mail an Konto #" + a.id());
                });
    }

    /** Neues Passwort per Reset-Link; beendet alle Sessions des Kontos und bestaetigt die E-Mail. @return Konto-id */
    public long reset(String token, char[] password) {
        long id = accounts.consumeEmailToken(RESET, token)
                .orElseThrow(() -> new Rejected(400, "Der Link ist ungültig oder abgelaufen"));
        accounts.updatePassword(id, Passwords.hash(password));
        accounts.markVerified(id);
        accounts.revokeSessions(id);
        LOG.info("Passwort von Konto #" + id + " per Mail zurueckgesetzt");
        return id;
    }

    /** Unbestaetigte Konten und abgelaufene Tokens aufraeumen (Start + stuendlich). */
    public void purge() {
        try {
            accounts.purgeUnverified(System.currentTimeMillis() - settings.unverifiedTtlMs());
        } catch (RuntimeException e) {
            LOG.warn("Aufraeumen unbestaetigter Konten fehlgeschlagen: " + e);
        }
    }
}
