package dev.magelite.auth;

/**
 * Angemeldeter Nutzer einer Anfrage. Im lokalen Modus immer {@link #LOCAL} (Nutzer 1, der lokale Held).
 * {@code email}/{@code hasPassword}: gesichertes Konto (sonst "Gast", nur per Einladungscode).
 */
public record User(long id, String name, boolean admin, String email, boolean hasPassword) {

    public static final User LOCAL = new User(1, "lokal", true);

    public User(long id, String name, boolean admin) {
        this(id, name, admin, null, false);
    }
}
