package dev.magelite.auth;

/**
 * Angemeldeter Nutzer einer Anfrage. Im lokalen Modus immer {@link #LOCAL} (Nutzer 1, der lokale Held).
 */
public record User(long id, String name, boolean admin) {

    public static final User LOCAL = new User(1, "lokal", true);
}
