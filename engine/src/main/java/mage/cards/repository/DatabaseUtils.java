package mage.cards.repository;

import mage.util.DebugUtil;

/**
 * MageLite-Ersatz fuer XMages {@code DatabaseUtils} (1.4.60) - muss vor den XMage-Jars auf dem Classpath liegen
 * (wie {@code GameStateEvaluator2}, Regel 10 in CLAUDE.md). Einziger Unterschied: die H2-URL nutzt das
 * {@code retry:}-Dateisystem ({@code org.h2.store.fs.FilePathRetryOnInterrupt}). Damit ueberlebt die Karten-DB
 * einen {@code Thread.interrupt()} waehrend eines Lesezugriffs (XMage-KI bricht Simulationen so ab); ohne das
 * schliesst Java den Dateikanal und jede weitere Abfrage scheitert ("file length -1") bis zum Neustart.
 * <p>
 * Konstanten und Aufbau 1:1 aus {@code javap -c mage.cards.repository.DatabaseUtils}. Dateipfad bleibt
 * {@code ./db/<name>}, vorhandene DBs bleiben gueltig.
 */
public class DatabaseUtils {

    public static final String DB_NAME_FEEDBACK = "feedback.h2";
    public static final String DB_NAME_USERS = "authorized_user.h2";
    public static final String DB_NAME_CARDS = "cards.h2";
    public static final String DB_NAME_RECORDS = "table_record.db";
    public static final String DB_NAME_STATS = "user_stats.db";

    public static String prepareH2Connection(String dbName, boolean isBigDatabase) {
        String res = String.format("jdbc:h2:retry:file:./db/%s", dbName);
        res += ";AUTO_SERVER=TRUE";
        res += ";IGNORECASE=TRUE";
        if (isBigDatabase) {
            res += ";CACHE_SIZE=" + Math.round(Math.max(150000.0d, Runtime.getRuntime().maxMemory() * 0.1d / 1024.0d));
            res += ";QUERY_CACHE_SIZE=32";
        }
        if (DebugUtil.DATABASE_PROFILE_SQL_QUERIES_TO_FILE) {
            res += ";TRACE_LEVEL_FILE=2";
            res += ";QUERY_STATISTICS=TRUE";
        }
        return res;
    }

    public static String prepareSqliteConnection(String dbName) {
        return String.format("jdbc:sqlite:./db/%s", dbName);
    }
}
