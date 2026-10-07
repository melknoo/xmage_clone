package dev.magelite.social;

import dev.magelite.stats.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Freundschaften (Tabelle {@code friendships}, ein Paar {@code a < b}) und die Lobby-Chat-Einstellung pro Konto.
 * Jede Abfrage ist auf den anfragenden Nutzer bezogen; Nutzer 1 (lokaler Held) nimmt nicht teil.
 */
public final class FriendStore {

    /** Eintrag aus Sicht von {@code me}: {@code state} = friend | incoming | outgoing. */
    public record Entry(long userId, String name, String state, long since) {
    }

    private final Db db;

    public FriendStore(Db db) {
        this.db = db;
    }

    public List<Entry> list(long me) {
        return db.with(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT u.id, u.name, f.requested_by, f.accepted_at, f.created_at FROM friendships f "
                            + "JOIN users u ON u.id = CASE WHEN f.a = ? THEN f.b ELSE f.a END "
                            + "WHERE f.a = ? OR f.b = ? ORDER BY u.name COLLATE NOCASE")) {
                ps.setLong(1, me);
                ps.setLong(2, me);
                ps.setLong(3, me);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long accepted = rs.getLong(4);
                        boolean isAccepted = !rs.wasNull();
                        String state = isAccepted ? "friend" : rs.getLong(3) == me ? "outgoing" : "incoming";
                        out.add(new Entry(rs.getLong(1), rs.getString(2), state, isAccepted ? accepted : rs.getLong(5)));
                    }
                }
            }
            return out;
        });
    }

    /** Konto-ID zu einem Namen (Gross-/Kleinschreibung egal, exakt). */
    public long idByName(String name) {
        List<Long> ids = db.with(c -> {
            List<Long> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE id != 1 AND name = ? COLLATE NOCASE")) {
                ps.setString(1, name.strip());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getLong(1));
                    }
                }
            }
            return out;
        });
        if (ids.isEmpty()) {
            throw new SocialService.SocialException("Kein Konto mit dem Namen „" + name.strip() + "“.");
        }
        if (ids.size() > 1) {
            throw new SocialService.SocialException("Den Namen gibt es mehrfach – klick die Person im Lobby-Chat an");
        }
        return ids.get(0);
    }

    public String nameOf(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM users WHERE id = ? AND id != 1")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /**
     * Freundschaftsanfrage. Liegt schon eine Anfrage der Gegenseite vor, wird sie angenommen.
     * @return "outgoing" (Anfrage gestellt) oder "friend" (jetzt befreundet)
     */
    public String request(long me, long other) {
        if (other == me) {
            throw new SocialService.SocialException("Du kannst dich nicht selbst hinzufügen");
        }
        String otherName = other == 1 ? null : nameOf(other);
        if (otherName == null) {
            throw new IllegalArgumentException("Unbekannter Nutzer");
        }
        long a = Math.min(me, other);
        long b = Math.max(me, other);
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT requested_by, accepted_at FROM friendships WHERE a = ? AND b = ?")) {
                ps.setLong(1, a);
                ps.setLong(2, b);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long by = rs.getLong(1);
                        rs.getLong(2);
                        if (!rs.wasNull()) {
                            throw new SocialService.SocialException(otherName + " ist schon dein Freund.");
                        }
                        if (by == me) {
                            throw new SocialService.SocialException("Anfrage an " + otherName + " läuft bereits.");
                        }
                        acceptRow(c, a, b);
                        return "friend";
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO friendships (a, b, requested_by, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, a);
                ps.setLong(2, b);
                ps.setLong(3, me);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return "outgoing";
        });
    }

    /** Eingehende Anfrage annehmen. */
    public void accept(long me, long other) {
        long a = Math.min(me, other);
        long b = Math.max(me, other);
        int n = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE friendships SET accepted_at = ? WHERE a = ? AND b = ? AND accepted_at IS NULL AND requested_by != ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, a);
                ps.setLong(3, b);
                ps.setLong(4, me);
                return ps.executeUpdate();
            }
        });
        if (n == 0) {
            throw new SocialService.SocialException("Keine offene Anfrage");
        }
    }

    private static void acceptRow(Connection c, long a, long b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE friendships SET accepted_at = ? WHERE a = ? AND b = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, a);
            ps.setLong(3, b);
            ps.executeUpdate();
        }
    }

    /** Ablehnen, zurueckziehen oder entfernen. @return true, wenn es etwas zu loeschen gab */
    public boolean remove(long me, long other) {
        long a = Math.min(me, other);
        long b = Math.max(me, other);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM friendships WHERE a = ? AND b = ?")) {
                ps.setLong(1, a);
                ps.setLong(2, b);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean isFriend(long me, long other) {
        long a = Math.min(me, other);
        long b = Math.max(me, other);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM friendships WHERE a = ? AND b = ? AND accepted_at IS NOT NULL")) {
                ps.setLong(1, a);
                ps.setLong(2, b);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** Ist der Nutzer im Lobby-Chat (Standard: ja)? */
    public boolean chatIn(long me) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT lobby_chat FROM users WHERE id = ?")) {
                ps.setLong(1, me);
                try (ResultSet rs = ps.executeQuery()) {
                    return !rs.next() || rs.getInt(1) != 0;
                }
            }
        });
    }

    public void setChatIn(long me, boolean in) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET lobby_chat = ? WHERE id = ?")) {
                ps.setInt(1, in ? 1 : 0);
                ps.setLong(2, me);
                return ps.executeUpdate();
            }
        });
    }
}
