package dev.magelite.auth;

import dev.magelite.stats.Db;
import dev.magelite.stats.ProfileService;
import org.apache.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Konten im Server-Modus: ein Konto pro Einladungscode. Nutzer 1 ist der lokale Held und hat keinen Code.
 */
public final class AccountService {

    private static final Logger LOG = Logger.getLogger(AccountService.class);
    private static final long TOUCH_INTERVAL_MS = 60_000;

    public record Account(long id, String name, boolean admin, long createdAt, Long lastSeen) {
    }

    public record Created(long id, String name, String code) {
    }

    private final Db db;
    private final Map<Long, Long> lastTouch = new ConcurrentHashMap<>();
    private volatile LongConsumer onRevoke = id -> {
    };

    public AccountService(Db db) {
        this.db = db;
    }

    /** Wird bei Rotieren/Entfernen aufgerufen (z.B. um WebSockets des Nutzers zu schliessen). */
    public void setOnRevoke(LongConsumer cb) {
        this.onRevoke = cb;
    }

    public Optional<User> byHash(String hash) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, is_admin FROM users WHERE code_hash = ?")) {
                ps.setString(1, hash);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new User(rs.getLong(1), rs.getString(2), rs.getInt(3) != 0)) : Optional.<User>empty();
                }
            }
        });
    }

    public List<Account> list() {
        return db.with(c -> {
            List<Account> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, is_admin, created_at, last_seen FROM users WHERE id != 1 ORDER BY id")) {
                while (rs.next()) {
                    long seen = rs.getLong(5);
                    out.add(new Account(rs.getLong(1), rs.getString(2), rs.getInt(3) != 0, rs.getLong(4), rs.wasNull() ? null : seen));
                }
            }
            return out;
        });
    }

    /** Neues Konto; der Code wird genau einmal zurueckgegeben. */
    public Created create(String name) {
        String code = InviteCodes.generate();
        long id = db.tx(c -> {
            long newId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (name, code_hash, is_admin, created_at) VALUES (?, ?, 0, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, InviteCodes.hash(code));
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    newId = keys.getLong(1);
                }
            }
            ProfileService.ensure(c, newId, name);
            return newId;
        });
        LOG.info("Einladung angelegt: " + name + " (#" + id + ")");
        return new Created(id, name, code);
    }

    /** Neuer Code fuer ein Konto; der alte ist sofort ungueltig. */
    public Optional<String> rotate(long id) {
        if (id == 1) {
            return Optional.empty();
        }
        String code = InviteCodes.generate();
        int n = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET code_hash = ? WHERE id = ? AND id != 1")) {
                ps.setString(1, InviteCodes.hash(code));
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        });
        if (n == 0) {
            return Optional.empty();
        }
        onRevoke.accept(id);
        return Optional.of(code);
    }

    /** Entfernt Konto, Profil und Decks; Spiele bleiben fuer die Statistik. */
    public boolean delete(long id) {
        if (id == 1) {
            return false;
        }
        boolean ok = db.tx(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ? AND id != 1")) {
                ps.setLong(1, id);
                n = ps.executeUpdate();
            }
            if (n == 0) {
                return false;
            }
            for (String sql : new String[]{"DELETE FROM profile WHERE id = ?", "DELETE FROM decks WHERE user_id = ?"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            return true;
        });
        if (ok) {
            onRevoke.accept(id);
        }
        return ok;
    }

    /**
     * Admin-Konto aus den Umgebungsvariablen (fly-Secrets). Gibt es schon einen Admin ausser Nutzer 1, bekommt er
     * den aktuellen Code (so laesst sich der Owner-Code ueber die Secrets rotieren).
     */
    public void ensureOwner(String code, String name) {
        String hash = InviteCodes.hash(code);
        db.tx(c -> {
            Long admin = null;
            boolean sameHash = false;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, code_hash FROM users WHERE is_admin = 1 AND id != 1 ORDER BY id LIMIT 1")) {
                if (rs.next()) {
                    admin = rs.getLong(1);
                    sameHash = hash.equals(rs.getString(2));
                }
            }
            if (admin == null) {
                long id;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users (name, code_hash, is_admin, created_at) VALUES (?, ?, 1, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name);
                    ps.setString(2, hash);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
                ProfileService.ensure(c, id, name);
                LOG.info("Owner-Konto angelegt: " + name + " (#" + id + ")");
            } else if (!sameHash) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE users SET code_hash = ? WHERE id = ?")) {
                    ps.setString(1, hash);
                    ps.setLong(2, admin);
                    ps.executeUpdate();
                }
                LOG.info("Owner-Code aktualisiert (#" + admin + ")");
            }
            return null;
        });
    }

    /** "zuletzt gesehen", hoechstens einmal pro Minute geschrieben. */
    public void touch(long id) {
        long now = System.currentTimeMillis();
        Long last = lastTouch.get(id);
        if (last != null && now - last < TOUCH_INTERVAL_MS) {
            return;
        }
        lastTouch.put(id, now);
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET last_seen = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        });
    }
}
