package dev.magelite.boot;

import dev.magelite.ForgeTestSupport;
import forge.StaticData;
import forge.deck.DeckFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forge-Boot: Kennzahlen der Karten-DB und Hygiene (Schreibzugriffe nur im Datenordner). */
class ForgeBootTest {

    private static ForgeBoot.Info info;

    @BeforeAll
    static void boot() {
        info = ForgeTestSupport.boot();
    }

    @Test
    void bootInfoMatchesDatabase() {
        assertNotNull(info);
        assertTrue(info.cards() > 25_000, "Karten: " + info.cards());
        assertTrue(info.editions() > 500, "Editionen: " + info.editions());
        assertTrue(info.tokens() > 500, "Token: " + info.tokens());
        assertFalse(info.commit().isBlank());
        assertFalse(info.forgeVersion().isBlank());
        assertSame(info, ForgeBoot.info());
        StaticData db = StaticData.instance();
        assertEquals(info.cards(), db.getCommonCards().getUniqueCards().size());
        assertEquals(info.editions(), db.getEditions().size());
        assertNotNull(db.getCommanderPredicate(), "Commander-Praedikat (Bans) muss gesetzt sein");
    }

    @Test
    void bootIsIdempotent() throws Exception {
        long t0 = System.currentTimeMillis();
        ForgeBoot.Info again = ForgeBoot.init(ForgeTestSupport.forgeHome(), ForgeTestSupport.dataDir());
        assertSame(info, again);
        assertTrue(System.currentTimeMillis() - t0 < 1000, "zweiter Boot muss sofort zurueckkehren");
    }

    @Test
    void commanderFormatKnowsBansAndSize() {
        assertTrue(DeckFormat.Commander.hasCommander());
        assertEquals(99, DeckFormat.Commander.getMainRange().getMinimum());
    }

    @Test
    void forgeWritesOnlyInsideDataDir() {
        assertEquals(java.util.List.of(), ForgeTestSupport.outsideWrites(), "Forge hat ausserhalb des Datenordners geschrieben");
        Path data = ForgeTestSupport.dataDir();
        assertTrue(Files.isDirectory(data.resolve("forge-data")) || Files.isDirectory(data.resolve("logs")),
                "forge-data/ bzw. logs/ muessen im Datenordner " + data + " liegen");
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }
}
