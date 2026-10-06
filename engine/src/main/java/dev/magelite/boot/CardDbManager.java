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
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

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

        warmNames(repo);
        checkRetryFs();

        LOG.info("Karten-DB bereit: oeffnen " + (tOpen - t0) + " ms, gesamt " + (System.currentTimeMillis() - t0)
                + " ms (" + repo.name() + "/" + expansions.name() + ")");
        return scanned;
    }

    /**
     * Laedt alle Kartennamen-Listen in den statischen Cache von {@link CardRepository}.
     * <p>
     * Hintergrund: XMage cacht diese Listen erst nach einer <em>erfolgreichen</em> Abfrage. Fragt eine
     * KI-Simulation (z.B. Demonic Consultation) sie zum ersten Mal ab und wird dabei per Timeout unterbrochen,
     * schliesst Java den H2-Dateikanal ({@code ClosedByInterruptException}), die Liste bleibt leer und
     * {@code ChooseACardNameEffect} wirft spaeter "Critical error, can't find card names in database".
     * Vorgeladen fasst waehrend des Spiels niemand mehr die DB fuer Namenslisten an.
     */
    public static void warmNames(CardRepository repo) {
        long t0 = System.currentTimeMillis();
        List<Supplier<Set<String>>> lists = List.of(
                repo::getNames, repo::getLandNames, repo::getNonLandNames, repo::getNonbasicLandNames,
                repo::getNotBasicLandNames, repo::getCreatureNames, repo::getArtifactNames,
                repo::getNonLandAndNonCreatureNames, repo::getNonArtifactAndNonLandNames);
        int total = 0;
        int empty = 0;
        for (Supplier<Set<String>> s : lists) {
            Set<String> names = s.get();
            total += names.size();
            if (names.isEmpty()) {
                empty++;
            }
        }
        String msg = "Kartennamen vorgeladen: " + lists.size() + " Listen, " + total + " Eintraege, "
                + (System.currentTimeMillis() - t0) + " ms";
        if (empty > 0) {
            LOG.error(msg + " - " + empty + " Liste(n) LEER, Karten-DB vermutlich defekt");
        } else {
            LOG.info(msg);
        }
    }

    /**
     * Prueft, ob unsere Ersatzklasse {@code mage.cards.repository.DatabaseUtils} (H2 mit {@code retry:}-Dateisystem,
     * das unterbrochene Dateizugriffe wiederholt statt den Kanal zu schliessen) vor den XMage-Jars auf dem
     * Classpath liegt (Regel 10 in CLAUDE.md).
     */
    public static boolean checkRetryFs() {
        boolean ok = mage.cards.repository.DatabaseUtils.prepareH2Connection("x", false).startsWith("jdbc:h2:retry:");
        if (ok) {
            LOG.info("Karten-DB: interrupt-sicherer Dateizugriff aktiv (DatabaseUtils retry:)");
        } else {
            LOG.warn("Karten-DB: XMage-DatabaseUtils aktiv (Engine-Jar nicht vor den XMage-Jars?) - "
                    + "ein Bot-Timeout waehrend eines DB-Zugriffs kann die DB bis zum Neustart kaputt machen");
        }
        return ok;
    }
}
