package dev.magelite.auth;

/**
 * Grenzen fuer oeffentliche (selbst registrierte) Konten im Server-Modus. Die Exceptions werden in
 * {@code HttpServer} auf JSON-Antworten abgebildet ({@code budget: true} bzw. {@code publicLimit: true}).
 */
public final class Limits {

    private Limits() {
    }

    /** 503: Monatsbudget erschoepft, oeffentliche Konten sind bis Monatsende gesperrt. */
    public static final class BudgetExhausted extends RuntimeException {
        public BudgetExhausted() {
            super("Das Server-Kontingent für diesen Monat ist aufgebraucht");
        }
    }

    /** 403: oeffentliche Konten duerfen keine Spiele auf dem Server rechnen lassen (nur Tische auf dem eigenen Rechner). */
    public static final class ServerGamesForbidden extends RuntimeException {
        public ServerGamesForbidden(String message) {
            super(message);
        }
    }

    /** Wirft {@link ServerGamesForbidden}, wenn der Nutzer kein Freund ist. */
    public static void requireServerGames(User u, String message) {
        if (!u.friend()) {
            throw new ServerGamesForbidden(message);
        }
    }
}
