package dev.magelite.deck;

import dev.magelite.boot.CardDbManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDeckParserTest {

    @BeforeAll
    static void db() throws Exception {
        CardDbManager.ensure(Path.of(System.getProperty("magelite.vendor"), "db", "cards.h2.mv.db"));
    }

    @Test
    void mtgaFormatWithSections() {
        String text = """
                Commander
                1 Atraxa, Praetors' Voice (CM2) 10

                Deck
                1 Sol Ring (C21) 263
                1 Arcane Signet (M3C) 283 *F*
                35 Forest
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(1, r.commanders().size());
        assertEquals("Atraxa, Praetors' Voice", r.commanders().get(0).name());
        assertFalse(r.needsCommander());
        assertEquals(38, r.cardCount());
        assertTrue(r.unknown().isEmpty(), r.unknown().toString());
    }

    @Test
    void archidektCategories() {
        String text = """
                1x Sol Ring (c21) 263 [Ramp]
                1x Kenrith, the Returned King (eld) 303 [Commander{top}]
                1x Some Card [Maybeboard{noDeck}{noPrice}]
                10x Plains (znr) 266 [Land]
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals("Kenrith, the Returned King", r.commanders().get(0).name());
        assertEquals(12, r.cardCount());
    }

    @Test
    void plainListBlankLineCommander() {
        String text = """
                1 Sol Ring
                1 Command Tower
                30 Island

                1 Talrand, Sky Summoner
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(List.of("Talrand, Sky Summoner"), r.commanders().stream().map(TextDeckParser.Resolved::name).toList());
        assertEquals(33, r.cardCount());
    }

    @Test
    void noCommanderGivesCandidatesAndForcedWorks() {
        String text = """
                1 Sol Ring
                1 Talrand, Sky Summoner
                1 Hullbreaker Horror
                30 Island
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertTrue(r.needsCommander());
        assertTrue(r.candidates().contains("Talrand, Sky Summoner"), r.candidates().toString());
        TextDeckParser.Result f = TextDeckParser.parse(text, null, List.of("Talrand, Sky Summoner"));
        assertEquals(1, f.commanders().size());
        assertEquals(33, f.cardCount());
        assertEquals(32, f.main().stream().mapToInt(TextDeckParser.Resolved::count).sum());
    }

    @Test
    void groupedListWithBlankLinesStaysMain() {
        String text = """
                1 Sol Ring
                1 Arcane Signet

                1 Llanowar Elves
                1 Elvish Mystic
                1 Fyndhorn Elves

                30 Forest
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(35, r.cardCount());
        assertTrue(r.needsCommander());
    }

    @Test
    void doubleFacedAndUnknown() {
        String text = """
                Commander
                1 Valki, God of Lies // Tibalt, Cosmic Impostor (KHM) 114
                Deck
                1 Totally Fake Card Name
                1 Fire // Ice
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(1, r.commanders().size(), r.unknown().toString());
        assertEquals(List.of("1 Totally Fake Card Name"), r.unknown());
        assertEquals(2, r.cardCount());
    }

    @Test
    void dckRoundTrip() {
        String text = "1 [C21:263] Sol Ring\nSB: 1 [ELD:303] Kenrith, the Returned King\n";
        TextDeckParser.Result r = TextDeckParser.parse(text, "Test", null);
        assertEquals("Kenrith, the Returned King", r.commanders().get(0).name());
        TextDeckParser.Result again = TextDeckParser.parse(DeckRoutes.dckToText(r.toDck()), "Test", null);
        assertEquals(r.cardCount(), again.cardCount());
        assertEquals(r.commanders().get(0).name(), again.commanders().get(0).name());
    }
}
