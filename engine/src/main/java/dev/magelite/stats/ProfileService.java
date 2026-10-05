package dev.magelite.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Der Held eines Nutzers: Name, XP, Level, Titel. {@code profile.id} = Nutzer-id; lokal immer 1.
 */
public final class ProfileService {

    private final Db db;

    public ProfileService(Db db) {
        this.db = db;
        db.with(c -> {
            ensure(c, 1, "Planeswalker");
            return null;
        });
    }

    /** Legt die Profilzeile an, falls sie fehlt. */
    public static void ensure(Connection c, long userId, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO profile (id, name, xp_total, created_at) VALUES (?, ?, 0, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    long xpTotal(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT xp_total FROM profile WHERE id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    void setXpTotal(Connection c, long userId, long xp) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE profile SET xp_total = ? WHERE id = ?")) {
            ps.setLong(1, xp);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public String name(long userId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM profile WHERE id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : "Planeswalker";
                }
            }
        });
    }

    public void rename(long userId, String name) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE profile SET name = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setLong(2, userId);
                return ps.executeUpdate();
            }
        });
    }

    public Map<String, Object> view(long userId) {
        return db.with(c -> {
            ensure(c, userId, "Planeswalker");
            Map<String, Object> m = new LinkedHashMap<>();
            String name;
            long xp;
            try (PreparedStatement ps = c.prepareStatement("SELECT name, xp_total FROM profile WHERE id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    name = rs.getString(1);
                    xp = rs.getLong(2);
                }
            }
            Progression.Level lv = Progression.levelOf(xp);
            m.put("name", name);
            m.put("level", lv.level());
            m.put("title", Progression.titleOf(lv.level()));
            m.put("xpTotal", xp);
            m.put("xpIntoLevel", lv.xpIntoLevel());
            m.put("xpForNext", lv.xpForNext());
            Progression.Title next = Progression.nextTitle(lv.level());
            if (next != null) {
                m.put("nextTitle", Map.of("level", next.level(), "title", next.title()));
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), SUM(CASE WHEN result='win' THEN 1 ELSE 0 END) FROM games WHERE end_reason != 'error' AND user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    m.put("games", rs.getInt(1));
                    m.put("wins", rs.getInt(2));
                }
            }
            m.put("streak", GameRecorder.winStreak(c, userId));
            return m;
        });
    }
}
