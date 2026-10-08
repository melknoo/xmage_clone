package dev.magelite.auth;

/**
 * Angemeldeter Nutzer einer Anfrage. Im lokalen Modus immer {@link #LOCAL} (Nutzer 1, der lokale Held).
 * {@code email}/{@code hasPassword}: gesichertes Konto (sonst "Gast", nur per Einladungscode).
 * {@code tier}: {@value #FRIEND} (eingeladen oder Owner) oder {@value #PUBLIC} (selbst registriert; darf keine
 * Spiele auf dem Server rechnen lassen und faellt unter das Monatsbudget).
 */
public record User(long id, String name, boolean admin, String email, boolean hasPassword, String tier) {

    public static final String FRIEND = "friend";
    public static final String PUBLIC = "public";
    public static final User LOCAL = new User(1, "lokal", true);

    public User(long id, String name, boolean admin) {
        this(id, name, admin, null, false, FRIEND);
    }

    /** Eingeladenes Konto oder Admin: Server-Spiele erlaubt, nicht vom Budget begrenzt. */
    public boolean friend() {
        return admin || !PUBLIC.equals(tier);
    }

    public User withCredentials(String newEmail) {
        return new User(id, name, admin, newEmail, true, tier);
    }
}
