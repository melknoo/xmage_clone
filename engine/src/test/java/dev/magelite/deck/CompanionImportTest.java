package dev.magelite.deck;

import dev.magelite.ForgeTestSupport;
import forge.deck.DeckSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gefaehrte (Companion): Text-, Archidekt- und Moxfield-Import, Decktext v2, Forge-Sideboard. */
class CompanionImportTest {

    private static final String LURRUS = "Lurrus of the Dream-Den";
    private static final String TEYSA = "Teysa Karlov";
    private static final String BASICS = "49 Plains\n50 Swamp\n";

    @BeforeAll
    static void boot() {
        ForgeTestSupport.boot();
    }

    private static List<String> names(List<TextDeckParser.Resolved> l) {
        return l.stream().map(TextDeckParser.Resolved::name).toList();
    }

    @Test
    void mtgaCompanionSection() {
        String text = "Companion\n1 " + LURRUS + "\n\nCommander\n1 " + TEYSA + "\n\nDeck\n" + BASICS;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(List.of(TEYSA), names(r.commanders()));
        assertEquals(List.of(LURRUS), names(r.companions()));
        assertEquals(100, r.cardCount(), "Gefaehrte zaehlt nicht zu den 100");

        String v2 = r.toText();
        assertTrue(v2.contains("\nCompanion\n1 " + LURRUS + " ("), v2);
        assertTrue(v2.indexOf("Companion\n") < v2.indexOf("Deck\n"), v2);
        TextDeckParser.Result again = TextDeckParser.parse(v2, null, null);
        assertEquals(List.of(LURRUS), names(again.companions()));
        assertEquals(List.of(TEYSA), names(again.commanders()));

        LoadedDeck d = DeckLoader.fromParsed(r, "test");
        assertTrue(d.valid(), d.validationErrors());
        assertEquals(List.of(LURRUS), d.companions());
        assertEquals(99, d.mainCount());
        assertEquals(List.of(LURRUS), d.newDeck().get(DeckSection.Sideboard).toFlatList().stream().map(c -> c.getName()).toList());
    }

    @Test
    void noCompanionKeepsTextAsBefore() {
        TextDeckParser.Result r = TextDeckParser.parse("Commander\n1 " + TEYSA + "\nDeck\n" + BASICS, null, null);
        assertTrue(r.companions().isEmpty());
        assertFalse(r.toText().contains("Companion"), r.toText());
        assertTrue(DeckLoader.fromParsed(r, "test").companions().isEmpty());
    }

    @Test
    void archidektCompanionCategory() {
        String text = "1x " + TEYSA + " [Commander{top}]\n1x " + LURRUS + " [Companion{noDeck}]\n49x Plains [Land]\n50x Swamp [Land]\n";
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(List.of(TEYSA), names(r.commanders()));
        assertEquals(List.of(LURRUS), names(r.companions()));
        assertEquals(100, r.cardCount());
    }

    @Test
    void mtgoSideboardCommanderAndCompanion() {
        TextDeckParser.Result r = TextDeckParser.parse(BASICS + "\n1 " + TEYSA + "\n1 " + LURRUS + "\n", null, null);
        assertEquals(List.of(TEYSA), names(r.commanders()));
        assertEquals(List.of(LURRUS), names(r.companions()));
    }

    @Test
    void sideboardCompanionWithFixedCommander() {
        String text = "Commander\n1 " + TEYSA + "\nDeck\n" + BASICS + "Sideboard\n1 " + LURRUS + "\n1 Sol Ring\n";
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(List.of(LURRUS), names(r.companions()));
        assertFalse(names(r.main()).contains("Sol Ring"), "uebriges Sideboard bleibt draussen");
    }

    @Test
    void forcedCommanderKeepsSideboardCompanion() {
        String text = "1 " + TEYSA + "\n" + BASICS + "Sideboard\n1 " + LURRUS + "\n";
        TextDeckParser.Result r = TextDeckParser.parse(text, null, List.of(TEYSA));
        assertEquals(List.of(TEYSA), names(r.commanders()));
        assertEquals(List.of(LURRUS), names(r.companions()));
        assertFalse(names(r.main()).contains(LURRUS));
    }

    @Test
    void companionAsCommanderIsNoCompanion() {
        TextDeckParser.Result r = TextDeckParser.parse("Commander\n1 " + LURRUS + "\nCompanion\n1 " + LURRUS + "\nDeck\n" + BASICS, null, null);
        assertEquals(List.of(LURRUS), names(r.commanders()));
        assertTrue(r.companions().isEmpty());
    }

    @Test
    void offColorCompanionIsNotLegal() {
        String text = "Commander\n1 Krenko, Mob Boss\nCompanion\n1 " + LURRUS + "\nDeck\n99 Mountain\n";
        LoadedDeck d = DeckLoader.fromText(text, null, "test");
        assertFalse(d.valid(), "Lurrus passt nicht zu Krenkos Farbidentitaet");
    }

    @Test
    void moxfieldCompanionsBoard() throws Exception {
        String json = """
                {"name":"Teysa","boards":{
                  "commanders":{"cards":{"a":{"quantity":1,"card":{"name":"Teysa Karlov","set":"rna","cn":"212"}}}},
                  "companions":{"cards":{"b":{"quantity":1,"card":{"name":"Lurrus of the Dream-Den","set":"iko","cn":"226"}}}},
                  "mainboard":{"cards":{"c":{"quantity":49,"card":{"name":"Plains","set":"","cn":""}},
                                        "d":{"quantity":50,"card":{"name":"Swamp","set":"","cn":""}}}}}}
                """;
        DeckUrlImporter.Imported imp = new DeckUrlImporter().fromJson("https://api2.moxfield.com/v3/decks/all/x", json);
        assertTrue(imp.text().contains("Companion\n1 " + LURRUS), imp.text());
        TextDeckParser.Result r = TextDeckParser.parse(imp.text(), null, null);
        assertEquals(List.of(LURRUS), names(r.companions()));
        assertEquals(100, r.cardCount());
    }

    @Test
    void moxfieldSideboardCompanion() throws Exception {
        String json = """
                {"name":"Teysa","boards":{
                  "commanders":{"cards":{"a":{"quantity":1,"card":{"name":"Teysa Karlov","set":"","cn":""}}}},
                  "sideboard":{"cards":{"b":{"quantity":1,"card":{"name":"Lurrus of the Dream-Den","set":"","cn":""}},
                                        "e":{"quantity":1,"card":{"name":"Sol Ring","set":"","cn":""}}}},
                  "mainboard":{"cards":{"c":{"quantity":49,"card":{"name":"Plains","set":"","cn":""}},
                                        "d":{"quantity":50,"card":{"name":"Swamp","set":"","cn":""}}}}}}
                """;
        DeckUrlImporter.Imported imp = new DeckUrlImporter().fromJson("https://api2.moxfield.com/v3/decks/all/x", json);
        TextDeckParser.Result r = TextDeckParser.parse(imp.text(), null, null);
        assertEquals(List.of(LURRUS), names(r.companions()));
        assertFalse(imp.text().contains("Sol Ring"), imp.text());
    }

    @Test
    void archidektCompanionCategoryJson() throws Exception {
        String json = """
                {"name":"Teysa","categories":[{"name":"Commander","isPremier":true,"includedInDeck":true},
                                              {"name":"Companion","isPremier":false,"includedInDeck":false},
                                              {"name":"Maybeboard","isPremier":false,"includedInDeck":false}],
                 "cards":[
                  {"quantity":1,"categories":["Commander"],"card":{"oracleCard":{"name":"Teysa Karlov"},"edition":{"editioncode":"rna"},"collectorNumber":"212"}},
                  {"quantity":1,"categories":["Companion"],"card":{"oracleCard":{"name":"Lurrus of the Dream-Den"},"edition":{"editioncode":"iko"},"collectorNumber":"226"}},
                  {"quantity":1,"categories":["Maybeboard"],"card":{"oracleCard":{"name":"Kaheera, the Orphanguard"},"edition":{"editioncode":""},"collectorNumber":""}},
                  {"quantity":49,"categories":["Land"],"card":{"oracleCard":{"name":"Plains"},"edition":{"editioncode":""},"collectorNumber":""}},
                  {"quantity":50,"categories":["Land"],"card":{"oracleCard":{"name":"Swamp"},"edition":{"editioncode":""},"collectorNumber":""}}]}
                """;
        DeckUrlImporter.Imported imp = new DeckUrlImporter().fromJson("https://archidekt.com/api/decks/1/", json);
        TextDeckParser.Result r = TextDeckParser.parse(imp.text(), null, null);
        assertEquals(List.of(TEYSA), names(r.commanders()));
        assertEquals(List.of(LURRUS), names(r.companions()), imp.text());
        assertFalse(imp.text().contains("Kaheera"), "Maybeboard-Gefaehrte bleibt draussen");
    }
}
