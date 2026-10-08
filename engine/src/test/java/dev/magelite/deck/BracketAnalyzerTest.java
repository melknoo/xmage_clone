package dev.magelite.deck;

import dev.magelite.boot.CardDbManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bracket-Vorschlag gegen die echte Karten-DB. */
class BracketAnalyzerTest {

    @BeforeAll
    static void db() throws Exception {
        CardDbManager.ensure(Path.of(System.getProperty("magelite.vendor"), "db", "cards.h2.mv.db"));
    }

    private static BracketAnalyzer.Result of(String... cards) {
        StringBuilder text = new StringBuilder("Commander\n1 Atraxa, Praetors' Voice\nDeck\n");
        for (String c : cards) {
            text.append("1 ").append(c).append('\n');
        }
        return DeckRoutes.bracketOf(TextDeckParser.parse(text.toString(), "Test", null));
    }

    private static List<String> kinds(BracketAnalyzer.Result r) {
        return r.reasons().stream().map(BracketAnalyzer.Reason::kind).toList();
    }

    @Test
    void plainDeckIsCore() {
        BracketAnalyzer.Result r = of("Llanowar Elves", "Counterspell", "Sol Ring", "Cultivate");
        assertEquals(2, r.bracket());
        assertTrue(r.reasons().isEmpty(), "keine Gruende: " + r.reasons());
    }

    @Test
    void gameChangerIsUpgraded() {
        BracketAnalyzer.Result r = of("Rhystic Study", "Llanowar Elves");
        assertEquals(3, r.bracket());
        assertEquals(List.of("gameChanger"), kinds(r));
    }

    @Test
    void extraTurnIsUpgraded() {
        assertEquals(3, of("Time Warp").bracket());
    }

    @Test
    void massLandDestructionIsOptimized() {
        BracketAnalyzer.Result r = of("Armageddon");
        assertEquals(4, r.bracket());
        assertTrue(kinds(r).contains("mld"));
    }

    @Test
    void twoCardComboIsOptimized() {
        BracketAnalyzer.Result r = of("Isochron Scepter", "Dramatic Reversal");
        assertEquals(4, r.bracket());
        assertTrue(kinds(r).contains("combo"), "Combo erwartet: " + r.reasons());
    }

    @Test
    void tutors() {
        assertTrue(BracketAnalyzer.isTutor("search your library for a card, put that card into your hand"));
        assertFalse(BracketAnalyzer.isTutor("search your library for a basic land card, put it onto the battlefield"));
        assertFalse(BracketAnalyzer.isTutor("search your library for up to two basic land cards"));
        assertFalse(BracketAnalyzer.isTutor("search your library for a forest card and a plains card"));
        assertTrue(BracketAnalyzer.isTutor("search your library for a creature card, reveal it"));
    }
}
