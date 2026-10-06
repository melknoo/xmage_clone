package dev.magelite.spike;

import dev.magelite.boot.CardDbManager;
import dev.magelite.boot.LogConfig;
import mage.cards.repository.CardRepository;
import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regressionstest fuer den Demonic-Consultation-Absturz: Ein Thread wird mitten in {@code CardRepository.getNames()}
 * (grosse DISTINCT-Abfrage) per {@code interrupt()} abgebrochen - genau wie XMages KI ihre Simulationen abbricht.
 * Ohne das {@code retry:}-Dateisystem (unsere {@code DatabaseUtils}) schliesst Java dabei den H2-Dateikanal und
 * die DB ist fuer den Prozess kaputt ("file length -1"). Danach muss die DB auf dem Main-Thread weiter
 * funktionieren.
 * <p>
 * Nicht Teil von {@code gradlew test}: ohne Haertung macht der Test die DB des Prozesses kaputt (das ist der Sinn).
 * Aufruf: {@code gradlew dbInterruptSpike [-PspikeArgs="--rounds=5"]}.
 */
public final class DbInterruptSpike {

    private static final Logger LOG = Logger.getLogger(DbInterruptSpike.class);

    private DbInterruptSpike() {
    }

    public static void main(String[] args) throws Exception {
        int rounds = 3;
        for (String a : args) {
            if (a.startsWith("--rounds=")) {
                rounds = Integer.parseInt(a.substring(9));
            }
        }
        Path vendor = Path.of(System.getProperty("magelite.vendor", "../../vendor/xmage")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, true);
        CardDbManager.ensure(vendor.resolve("db/cards.h2.mv.db"));
        boolean retry = CardDbManager.checkRetryFs();

        CardRepository repo = CardRepository.instance;
        int expected = repo.getNames().size();
        LOG.info("Referenz: " + expected + " Kartennamen");

        int broken = 0;
        for (int i = 1; i <= rounds; i++) {
            clearNamesCache();
            AtomicReference<Integer> fromThread = new AtomicReference<>();
            Thread t = new Thread(() -> fromThread.set(repo.getNames().size()), "db-interrupt-probe");
            t.start();
            // waehrend der ganzen Abfrage immer wieder unterbrechen (die DISTINCT-Abfrage dauert bei warmem
            // Page-Cache nur ~50 ms; ein einzelner Interrupt traefe sonst oft erst nach dem Ende)
            int interrupts = 0;
            while (t.isAlive() && interrupts < 10_000) {
                t.interrupt();
                interrupts++;
                Thread.sleep(0, 200_000);
            }
            t.join(30_000);

            clearNamesCache();
            int after = repo.getNames().size();
            boolean ok = after == expected && after > 20_000 && repo.findCard("Forest") != null;
            LOG.info("Runde " + i + ": " + interrupts + " Interrupts, im Thread " + fromThread.get() + ", danach " + after
                    + " -> " + (ok ? "OK" : "KAPUTT"));
            if (!ok) {
                broken++;
            }
        }
        System.out.println("dbInterruptSpike: retry-FS=" + (retry ? "aktiv" : "AUS") + ", " + rounds + " Runden, "
                + (rounds - broken) + " OK, " + broken + " KAPUTT -> " + (broken == 0 ? "OK" : "FEHLER"));
        System.exit(broken == 0 ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    private static void clearNamesCache() throws ReflectiveOperationException {
        Field f = CardRepository.class.getDeclaredField("namesQueryCache");
        f.setAccessible(true);
        ((Map<String, Set<String>>) f.get(null)).clear();
    }
}
