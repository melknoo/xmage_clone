package dev.magelite.auth;

import dev.magelite.stats.Db;
import dev.magelite.stats.ProfileService;
import org.apache.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Konten im Server-Modus: ein Konto pro Einladungscode ("Gast"); optional mit E-Mail + Passwort gesichert.
 * Anmeldungen (per Code oder E-Mail/Passwort) erzeugen eine Session (Tabelle {@code sessions}, Cookie mit
 * Zufallstoken). Nutzer 1 ist der lokale Held und hat weder Code noch Passwort.
 */
public final class AccountService {

    private static final Logger LOG = Logger.getLogger(AccountService.class);
    private static final long TOUCH_INTERVAL_MS = 60_000;
    private static final String USER_COLS = "id, name, is_admin, email, pw_hash IS NOT NULL";

    public record Account(long id, String name, boolean admin, long createdAt, Long lastSeen, String email, boolean hasPassword) {
    }

    public record Created(long id, String name, String code) {
    }

    /** Fachlicher Konflikt (409): E-Mail vergeben, Passwort schon gesetzt ... */
    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
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

    private static User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getLong(1), rs.getString(2), rs.getInt(3) != 0, rs.getString(4), rs.getInt(5) != 0);
    }

    /** Konto zum Hash eines Einladungscodes. */
    public Optional<User> byHash(String hash) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + " FROM users WHERE code_hash = ?")) {
                ps.setString(1, hash);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapUser(rs)) : Optional.<User>empty();
                }
            }
        });
    }

    /** Konto zu E-Mail + Passwort; unbekannte E-Mail kostet dieselbe Zeit (Dummy-Hash). */
    public Optional<User> byCredentials(String email, char[] password) {
        record Row(User user, String hash) {
        }
        Row row = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + ", pw_hash FROM users WHERE email = ? AND pw_hash IS NOT NULL")) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Row(mapUser(rs), rs.getString(6)) : null;
                }
            }
        });
        boolean ok = Passwords.verify(password, row == null ? null : row.hash());
        return ok && row != null ? Optional.of(row.user()) : Optional.empty();
    }

    public Optional<User> bySession(String tokenHash) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT u.id, u.name, u.is_admin, u.email, u.pw_hash IS NOT NULL FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?")) {
                ps.setString(1, tokenHash);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapUser(rs)) : Optional.<User>empty();
                }
            }
        });
    }

    /** Neue Session; zurueck kommt das Klartext-Token fuers Cookie. */
    public String createSession(long userId, String via) {
        String token = Passwords.newToken();
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sessions (user_id, token_hash, via, created_at, last_seen) VALUES (?, ?, ?, ?, ?)")) {
                long now = System.currentTimeMillis();
                ps.setLong(1, userId);
                ps.setString(2, Passwords.tokenHash(token));
                ps.setString(3, via);
                ps.setLong(4, now);
                ps.setLong(5, now);
                return ps.executeUpdate();
            }
        });
        return token;
    }

    public record SessionInfo(long since, String via) {
    }

    /** Session des Nutzers zum Token-Hash (fuer "angemeldet seit"); leer bei altem Code-Cookie oder fremdem Hash. */
    public Optional<SessionInfo> sessionInfo(long userId, String tokenHash) {
        if (tokenHash == null) {
            return Optional.empty();
        }
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT created_at, via FROM sessions WHERE token_hash = ? AND user_id = ?")) {
                ps.setString(1, tokenHash);
                ps.setLong(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new SessionInfo(rs.getLong(1), rs.getString(2))) : Optional.<SessionInfo>empty();
                }
            }
        });
    }

    public void deleteSession(String tokenHash) {
        if (tokenHash == null) {
            return;
        }
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?")) {
                ps.setString(1, tokenHash);
                return ps.executeUpdate();
            }
        });
    }

    public void deleteSessionsOf(long userId) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                ps.setLong(1, userId);
                return ps.executeUpdate();
            }
        });
    }

    /** Alle anderen Sessions des Nutzers beenden (nach Passwortwechsel). */
    public void deleteOtherSessions(long userId, String keepTokenHash) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?")) {
                ps.setLong(1, userId);
                ps.setString(2, keepTokenHash == null ? "" : keepTokenHash);
                return ps.executeUpdate();
            }
        });
    }

    public List<Account> list() {
        return db.with(c -> {
            List<Account> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, is_admin, created_at, last_seen, email, pw_hash IS NOT NULL FROM users WHERE id != 1 ORDER BY id")) {
                while (rs.next()) {
                    long seen = rs.getLong(5);
                    boolean noSeen = rs.wasNull();
                    out.add(new Account(rs.getLong(1), rs.getString(2), rs.getInt(3) != 0, rs.getLong(4), noSeen ? null : seen,
                            rs.getString(6), rs.getInt(7) != 0));
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

    /** Neuer Code fuer ein Konto; der alte ist sofort ungueltig, alle Sessions enden. */
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
        deleteSessionsOf(id);
        onRevoke.accept(id);
        return Optional.of(code);
    }

    /** Entfernt Konto, Sessions, Profil und Decks; Spiele bleiben fuer die Statistik. */
    public boolean delete(long id) {
        if (id == 1) {
            return false;
        }
        boolean ok = db.tx(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
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

    /** Konto sichern: E-Mail + Passwort-Hash setzen (nur wenn noch keines gesetzt ist). */
    public void setCredentials(long id, String email, String pwHash) {
        int n;
        try {
            n = db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET email = ?, pw_hash = ?, pw_set_at = ? WHERE id = ? AND id != 1 AND pw_hash IS NULL")) {
                    ps.setString(1, email);
                    ps.setString(2, pwHash);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setLong(4, id);
                    return ps.executeUpdate();
                }
            });
        } catch (IllegalStateException e) {
            throw uniqueOr(e);
        }
        if (n == 0) {
            throw new Conflict("Dieses Konto hat schon ein Passwort");
        }
        LOG.info("Konto #" + id + " gesichert (E-Mail gesetzt)");
    }

    public void updateEmail(long id, String email) {
        try {
            db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE users SET email = ? WHERE id = ? AND id != 1")) {
                    ps.setString(1, email);
                    ps.setLong(2, id);
                    return ps.executeUpdate();
                }
            });
        } catch (IllegalStateException e) {
            throw uniqueOr(e);
        }
    }

    public void updatePassword(long id, String pwHash) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET pw_hash = ?, pw_set_at = ? WHERE id = ? AND id != 1")) {
                ps.setString(1, pwHash);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, id);
                return ps.executeUpdate();
            }
        });
    }

    /** Gespeicherter Passwort-Hash (fuer "aktuelles Passwort" pruefen). */
    public Optional<String> passwordHash(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT pw_hash FROM users WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    private static RuntimeException uniqueOr(IllegalStateException e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("UNIQUE") || msg.contains("users_email")) {
            return new Conflict("Diese E-Mail ist schon vergeben");
        }
        return e;
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

    /** "zuletzt gesehen" (Nutzer und Session), hoechstens einmal pro Minute geschrieben. */
    public void touch(long id, String sessionHash) {
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
                ps.executeUpdate();
            }
            if (sessionHash != null) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE sessions SET last_seen = ? WHERE token_hash = ?")) {
                    ps.setLong(1, now);
                    ps.setString(2, sessionHash);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public void touch(long id) {
        touch(id, null);
    }
}
