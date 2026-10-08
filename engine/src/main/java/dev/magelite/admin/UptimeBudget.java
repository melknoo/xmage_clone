package dev.magelite.admin;

import dev.magelite.auth.User;
import dev.magelite.stats.Db;
import org.apache.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.function.BiConsumer;

/**
 * Laufzeit-Budget pro Kalendermonat (UTC), Server-Modus. fly kennt kein hartes Ausgabenlimit, also zaehlt die Engine
 * ihre eigene Laufzeit (eine Minute pro {@link #tick()}) in {@code uptime_month}. Ist das Budget erschoepft, sind
 * oeffentliche Konten gesperrt und halten die Maschine nicht mehr wach; Freunde und Owner spielen weiter.
 * <p>
 * Der Zaehlerstand liegt zusaetzlich im Speicher, weil {@link #exhausted()} bei jeder Anfrage gefragt wird.
 */
public final class UptimeBudget {

    private static final Logger LOG = Logger.getLogger(UptimeBudget.class);

    public record Status(String month, long minutes, long budgetMin, double pricePerHour, boolean exhausted, long resetsAt) {
    }

    private final Db db;
    private final long budgetMin;
    private final double pricePerHour;
    private volatile String month;
    private volatile long minutes;
    /** (Prozent 80/100, Stand) - je Schwelle einmal pro Monat, Merker in {@code kv} */
    private volatile BiConsumer<Integer, Status> onThreshold = (pct, s) -> {
    };

    public UptimeBudget(Db db, long budgetMin, double pricePerHour) {
        this.db = db;
        this.budgetMin = budgetMin;
        this.pricePerHour = pricePerHour;
        this.month = currentMonth();
        this.minutes = load(month);
        LOG.info("Laufzeit-Budget: " + minutes + " von " + budgetMin + " min im " + month + (exhausted() ? " - erschoepft" : ""));
    }

    public void setOnThreshold(BiConsumer<Integer, Status> cb) {
        this.onThreshold = cb;
    }

    private static String currentMonth() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    private long load(String m) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT minutes FROM uptime_month WHERE month = ?")) {
                ps.setString(1, m);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** Eine Minute Laufzeit verbuchen (einmal pro Minute aus dem Leerlauf-Waechter). */
    public synchronized void tick() {
        String m = currentMonth();
        if (!m.equals(month)) {
            month = m;
            minutes = load(m);
        }
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO uptime_month (month, minutes) VALUES (?, 1) ON CONFLICT(month) DO UPDATE SET minutes = minutes + 1")) {
                ps.setString(1, m);
                return ps.executeUpdate();
            }
        });
        minutes++;
        checkThreshold(80);
        checkThreshold(100);
    }

    private void checkThreshold(int pct) {
        if (budgetMin <= 0 || minutes * 100 < budgetMin * pct) {
            return;
        }
        String key = "budget-warn-" + pct + "-" + month;
        boolean first = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO kv (key, value) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, String.valueOf(System.currentTimeMillis()));
                return ps.executeUpdate() > 0;
            }
        });
        if (first) {
            LOG.warn("Laufzeit-Budget " + pct + " % erreicht (" + minutes + "/" + budgetMin + " min)");
            try {
                onThreshold.accept(pct, status());
            } catch (RuntimeException e) {
                LOG.warn("Budget-Warnung fehlgeschlagen: " + e);
            }
        }
    }

    public boolean exhausted() {
        String m = currentMonth();
        if (!m.equals(month)) {
            return false; // neuer Monat, der naechste tick() liest den Stand neu
        }
        return minutes >= budgetMin;
    }

    /** Darf der Nutzer den Server nutzen (und ihn damit wachhalten)? */
    public boolean counts(User u) {
        return u.friend() || !exhausted();
    }

    public Status status() {
        long resetsAt = YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        return new Status(month, minutes, budgetMin, pricePerHour, exhausted(), resetsAt);
    }
}
