package dev.magelite.boot;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Raeumt Reste der XMage-Zeit aus dem Datenordner: die H2-Karten-DB unter {@code db/} (Desktop ~104 MB, fly ~63 MB).
 * Idempotent; {@code magelite.db} und alles andere bleibt unberuehrt.
 */
public final class LegacyCleanup {

    private static final Logger LOG = Logger.getLogger(LegacyCleanup.class);
    private static final List<String> FILES = List.of("cards.h2.mv.db", "cards.h2.trace.db", "cards.h2.lock.db");

    private LegacyCleanup() {
    }

    public static void run(Path data) {
        Path dbDir = data.resolve("db");
        if (!Files.isDirectory(dbDir)) {
            return;
        }
        long freed = 0;
        for (String name : FILES) {
            Path f = dbDir.resolve(name);
            try {
                if (Files.isRegularFile(f)) {
                    long size = Files.size(f);
                    Files.delete(f);
                    freed += size;
                }
            } catch (IOException e) {
                LOG.warn("XMage-Karten-DB nicht geloescht: " + f + " (" + e + ")");
            }
        }
        try (Stream<Path> rest = Files.list(dbDir)) {
            if (rest.findAny().isEmpty()) {
                Files.delete(dbDir);
            }
        } catch (IOException e) {
            LOG.warn("Leeres db/ nicht entfernt: " + e);
        }
        if (freed > 0) {
            LOG.info("XMage-Karten-DB entfernt: " + (freed / (1024 * 1024)) + " MB frei");
        }
    }
}
