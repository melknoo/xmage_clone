package dev.magelite.stats;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite-Datenbank fuer Decks, Statistiken und Profil. Eine Verbindung, synchronisierter Zugriff.
 * Migrationen: {@code /db/migrations/V<n>__*.sql} (fortlaufend nummeriert).
 */
public final class Db implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Db.class);
    private static final String[] MIGRATIONS = {"V1__init.sql"};

    private final Connection conn;

    public Db(Path file) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        int current = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            if (rs.next()) {
                current = rs.getInt(1);
            }
        }
        for (int i = current; i < MIGRATIONS.length; i++) {
            String sql = resource("/db/migrations/" + MIGRATIONS[i]);
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String part : sql.split(";\\s*\\n")) {
                    if (!part.isBlank()) {
                        st.execute(part);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
                    ps.setInt(1, i + 1);
                    ps.executeUpdate();
                }
                conn.commit();
                LOG.info("DB-Migration " + MIGRATIONS[i] + " angewendet");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static String resource(String path) {
        try (InputStream in = Db.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Migration fehlt: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Fuehrt Arbeit exklusiv auf der Verbindung aus. */
    public synchronized <T> T with(Work<T> work) {
        try {
            return work.run(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("DB-Fehler: " + e.getMessage(), e);
        }
    }

    /** Fuehrt Arbeit in einer Transaktion aus. */
    public synchronized <T> T tx(Work<T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DB-Fehler: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // egal
        }
    }
}
