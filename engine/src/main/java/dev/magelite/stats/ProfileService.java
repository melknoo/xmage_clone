package dev.magelite.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Der Held (ein Profil): Name, XP, Level, Titel.
 */
public final class ProfileService {

    private final Db db;

    public ProfileService(Db db) {
        this.db = db;
        db.with(c -> {
            ensure(c);
            return null;
        });
    }

    private static void ensure(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO profile (id, name, xp_total, created_at) VALUES (1, 'Planeswalker', 0, ?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    long xpTotal(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT xp_total FROM profile WHERE id = 1")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    void setXpTotal(Connection c, long xp) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE profile SET xp_total = ? WHERE id = 1")) {
            ps.setLong(1, xp);
            ps.executeUpdate();
        }
    }

    public String name() {
        return db.with(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM profile WHERE id = 1")) {
                return rs.next() ? rs.getString(1) : "Planeswalker";
            }
        });
    }

    public void rename(String name) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE profile SET name = ? WHERE id = 1")) {
                ps.setString(1, name);
                return ps.executeUpdate();
            }
        });
    }

    public Map<String, Object> view() {
        return db.with(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String name;
            long xp;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name, xp_total FROM profile WHERE id = 1")) {
                rs.next();
                name = rs.getString(1);
                xp = rs.getLong(2);
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
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*), SUM(CASE WHEN result='win' THEN 1 ELSE 0 END) FROM games WHERE end_reason != 'error'")) {
                rs.next();
                m.put("games", rs.getInt(1));
                m.put("wins", rs.getInt(2));
            }
            m.put("streak", GameRecorder.winStreak(c));
            return m;
        });
    }
}
