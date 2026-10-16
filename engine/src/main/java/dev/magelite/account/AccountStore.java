package dev.magelite.account;

import dev.magelite.stats.Db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Merker des Konto-Modus in der lokalen DB (Tabelle {@code kv}): Warteschlange nicht gemeldeter Spiele und welche
 * lokalen Decks schon ins Konto uebernommen wurden. Schluessel tragen Server und Konto-id, damit ein Kontowechsel
 * nichts vermischt.
 */
public final class AccountStore {

    private final Db db;

    public AccountStore(Db db) {
        this.db = db;
    }

    /** Praefix je Server und Konto, z.B. {@code account|https://magelite.fly.dev|7|}. */
    public static String scope(String server, long accountId) {
        return "account|" + server + "|" + accountId + "|";
    }

    public Optional<String> get(String key) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM kv WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    public void put(String key, String value) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                return ps.executeUpdate();
            }
        });
    }

    public void delete(String key) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kv WHERE key = ?")) {
                ps.setString(1, key);
                return ps.executeUpdate();
            }
        });
    }

    /** Alle Eintraege mit diesem Praefix (Schluessel -> Wert), aelteste zuerst. */
    public Map<String, String> withPrefix(String prefix) {
        return db.with(c -> {
            Map<String, String> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT key, value FROM kv WHERE substr(key, 1, ?) = ? ORDER BY rowid")) {
                ps.setInt(1, prefix.length());
                ps.setString(2, prefix);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return out;
        });
    }
}
