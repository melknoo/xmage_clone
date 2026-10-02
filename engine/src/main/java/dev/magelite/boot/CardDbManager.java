package dev.magelite.boot;

import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.cards.repository.ExpansionRepository;
import mage.cards.repository.RepositoryUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stellt die XMage-Karten-DB bereit, ohne bei jedem Start alle Karten zu scannen.
 * <p>
 * XMage oeffnet die DB immer unter {@code ./db/cards.h2} relativ zum Arbeitsverzeichnis.
 * Beim Erststart wird die vorgefertigte DB aus der XMage-Distribution kopiert.
 * {@link CardRepository} prueft beim Oeffnen selbst DB-Version und Jar-Build-Time und leert
 * die Tabellen, wenn sie nicht passen - nur dann ist {@link CardScanner#scan()} (~13 s) noetig.
 * Der XMage-Server scannt dagegen bei jedem Start.
 */
public final class CardDbManager {

    private static final Logger LOG = Logger.getLogger(CardDbManager.class);
    private static final String DB_FILE = "cards.h2.mv.db";

    private CardDbManager() {
    }

    /**
     * @param seedDb vorgefertigte {@code cards.h2.mv.db} (darf null sein)
     * @return true falls ein Karten-Scan noetig war
     */
    public static boolean ensure(Path seedDb) throws IOException {
        long t0 = System.currentTimeMillis();
        Path dbDir = Path.of("db").toAbsolutePath();
        Files.createDirectories(dbDir);
        Path dbFile = dbDir.resolve(DB_FILE);

        if (!Files.exists(dbFile) && seedDb != null && Files.exists(seedDb)) {
            LOG.info("Kopiere Karten-DB von " + seedDb);
            Files.copy(seedDb, dbFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // Oeffnet die DB; XMage prueft hier DB-Version und Build-Time (leert ggf. Tabellen)
        CardRepository repo = CardRepository.instance;
        ExpansionRepository expansions = ExpansionRepository.instance;
        long tOpen = System.currentTimeMillis();

        boolean scanned = false;
        if (RepositoryUtil.isDatabaseEmpty()) {
            LOG.warn("Karten-DB leer oder veraltet - indiziere Karten (einmalig, ~30 s) ...");
            long s0 = System.currentTimeMillis();
            CardScanner.scan();
            scanned = true;
            LOG.warn("Karten-Scan fertig in " + (System.currentTimeMillis() - s0) + " ms");
        } else {
            CardScanner.scanned = true; // verhindert spaetere Scans durch XMage-Code
        }

        LOG.info("Karten-DB bereit: oeffnen " + (tOpen - t0) + " ms, gesamt " + (System.currentTimeMillis() - t0)
                + " ms (" + repo.name() + "/" + expansions.name() + ")");
        return scanned;
    }
}
