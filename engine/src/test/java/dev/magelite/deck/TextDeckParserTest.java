package dev.magelite.deck;

import dev.magelite.ForgeTestSupport;
import forge.item.PaperCard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDeckParserTest {

    @BeforeAll
    static void boot() {
        ForgeTestSupport.boot();
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

    /** Decktext v2 -&gt; Text -&gt; Decktext v2 ist stabil (Namen, Sets, Nummern, Zahlen) und hat Commander zuerst, Deck nach Name. */
    @Test
    void textRoundTrip() {
        String text = """
                Commander
                1 Atraxa, Praetors' Voice (CM2) 10
                Deck
                1 Sol Ring (C21) 263
                1 Arcane Signet (M3C) 283
                1 Command Tower (C21) 276
                10 Island
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, "Test", null);
        String v2 = r.toText();
        assertTrue(v2.startsWith("Commander\n1 Atraxa, Praetors' Voice (CM2) 10\nDeck\n"), v2);
        List<String> names = r.main().stream().map(TextDeckParser.Resolved::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        assertEquals(names, v2.lines().skip(3).map(l -> l.replaceFirst("^\\d+ ", "").replaceFirst(" \\(.*$", "")).toList(),
                "Deck-Zeilen nach Name sortiert");
        assertTrue(v2.contains("1 Sol Ring (C21) 263\n"), v2);
        TextDeckParser.Result again = TextDeckParser.parse(v2, "Test", null);
        assertEquals(v2, again.toText(), "zweiter Durchlauf aendert nichts");
        assertEquals(r.cardCount(), again.cardCount());
        assertEquals(r.commanders().get(0).name(), again.commanders().get(0).name());
        assertEquals(r.commanders().get(0).set(), again.commanders().get(0).set());
        assertEquals(r.commanders().get(0).number(), again.commanders().get(0).number());
        assertEquals("NAME:Test\n" + v2, r.toText(true));
        assertEquals("Test", TextDeckParser.declaredName(r.toText(true)));
    }

    /** XMage-.dck (Migration, Pasten): {@code N [SET:num] Name}, Commander als {@code SB:}. */
    @Test
    void legacyXmageDckParses() {
        String text = "NAME:Alt\n1 [C21:263] Sol Ring\n3 [ZNR:266] Plains\nSB: 1 [ELD:303] Kenrith, the Returned King\nLAYOUT MAIN:(1,1)(NONE,false,50)|([C21:263])\n";
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals("Alt", r.name());
        assertEquals("Kenrith, the Returned King", r.commanders().get(0).name());
        assertEquals("ELD", r.commanders().get(0).set());
        assertEquals("303", r.commanders().get(0).number());
        assertEquals(4, r.main().stream().mapToInt(TextDeckParser.Resolved::count).sum());
        assertTrue(r.unknown().isEmpty(), r.unknown().toString());
        TextDeckParser.Result forced = TextDeckParser.parse(text, "Alt", List.of("Kenrith, the Returned King"));
        assertEquals(r.cardCount(), forced.cardCount());
        TextDeckParser.Result again = TextDeckParser.parse(DeckText.toEditable(text), "Alt", null);
        assertEquals(r.cardCount(), again.cardCount());
        assertEquals(r.commanders().get(0).name(), again.commanders().get(0).name());
        assertTrue(DeckText.toEditable(text).startsWith("Commander\n1 Kenrith, the Returned King (ELD) 303\n"), DeckText.toEditable(text));
    }

    /** Forge-.dck: Abschnitte in eckigen Klammern, {@code N Name|SET|art}, {@code [metadata] Name=}. */
    @Test
    void forgeDckSyntax() {
        String text = """
                [metadata]
                Name=Mein Forge Deck
                [Commander]
                1 Atraxa, Praetors' Voice|CM2|1
                [Main]
                1 Sol Ring+|C21|1
                1 Arcane Signet|M3C
                1 Command Tower
                10 Forest|ZNR|1
                [Planes]
                1 Some Plane|XXX
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals("Mein Forge Deck", r.name());
        assertEquals(List.of("Atraxa, Praetors' Voice"), r.commanders().stream().map(TextDeckParser.Resolved::name).toList());
        assertEquals(14, r.cardCount());
        assertTrue(r.unknown().isEmpty(), r.unknown().toString());
        TextDeckParser.Resolved forest = r.main().stream().filter(c -> c.name().equals("Forest")).findFirst().orElseThrow();
        assertEquals(10, forest.count());
        assertEquals("ZNR", forest.set());
        assertEquals("CM2", r.commanders().get(0).set());
        // Name gewinnt gegen [metadata]
        assertEquals("Anders", TextDeckParser.parse(text, "Anders", null).name());
    }

    /** Forge- und Scryfall-Code eines Sets sind verschieden (Nemesis: NMS/NEM): beide loesen auf, gespeichert wird Scryfall. */
    @Test
    void scryfallCodeDiffersFromForgeCode() {
        PaperCard viaScryfall = CardLookup.resolve("Blastoderm", "NEM", "102");
        PaperCard viaForge = CardLookup.resolve("Blastoderm", "NMS", "102");
        assertNotNull(viaScryfall);
        assertEquals("NMS", viaScryfall.getEdition(), "Forge-Code der Edition");
        assertEquals("NEM", CardLookup.scryfallSet(viaScryfall), "gespeichert wird der Scryfall-Code");
        assertEquals("102", CardLookup.number(viaScryfall));
        assertEquals(viaScryfall, viaForge);
        PaperCard conflux = CardLookup.resolve("Noble Hierarch", "con", "87");
        assertEquals("CFX", conflux.getEdition());
        assertEquals("CON", CardLookup.scryfallSet(conflux));
        TextDeckParser.Result r = TextDeckParser.parse("1 Blastoderm (NEM) 102\n1 Noble Hierarch (CON) 87\n", null, null);
        assertEquals("1 Blastoderm (NEM) 102", r.main().stream().filter(c -> c.name().equals("Blastoderm")).findFirst().orElseThrow().line());
        assertEquals("CON", r.main().stream().filter(c -> c.name().equals("Noble Hierarch")).findFirst().orElseThrow().set());
        // unbekanntes Set (Forge kennt "SUM" nicht): faellt auf den Namen zurueck statt zu scheitern
        PaperCard unknownSet = CardLookup.resolve("Sol Ring", "SUM", "274");
        assertNotNull(unknownSet);
        assertEquals("Sol Ring", unknownSet.getName());
        // Nummer abweichend (XMage fuehrt andere Basic-Land-Nummern): richtiges Set, anderer Druck
        PaperCard basic = CardLookup.resolve("Swamp", "CMD", "309");
        assertEquals("CMD", CardLookup.scryfallSet(basic));
    }

    /** Wende-/Split-/Abenteuerkarten: Forge fuehrt die Vorderseite (Split: beide Haelften) als Namen. */
    @Test
    void dfcFrontFaceName() {
        assertEquals("Valki, God of Lies", CardLookup.resolve("Valki, God of Lies // Tibalt, Cosmic Impostor", "KHM", "114").getName());
        assertEquals("Valki, God of Lies", CardLookup.resolve("Valki, God of Lies", null, null).getName());
        assertEquals("Valki, God of Lies", CardLookup.resolve("Tibalt, Cosmic Impostor", null, null).getName(), "Rueckseite -> Karte");
        assertEquals("Delver of Secrets", CardLookup.resolve("Delver of Secrets // Insectile Aberration", null, null).getName());
        assertEquals("Bonecrusher Giant", CardLookup.resolve("Bonecrusher Giant // Stomp", null, null).getName());
        assertEquals("Fire // Ice", CardLookup.resolve("Fire // Ice", null, null).getName());
        assertEquals("Fire // Ice", CardLookup.resolve("Fire / Ice", null, null).getName());
        assertEquals("Fire // Ice", CardLookup.resolve("Fire", null, null).getName());
        assertEquals("Jötun Grunt", CardLookup.resolve("Jotun Grunt", null, null).getName(), "Akzente wie Forge");
        assertEquals("Jötun Grunt", CardLookup.resolve("Jötun Grunt", null, null).getName());
        assertEquals("Aether Vial", CardLookup.resolve("Æther Vial", null, null).getName(), "Scryfall schreibt Æ, Forge Ae");
        assertEquals("Lim-Dûl's Vault", CardLookup.resolve("Lim-Dul’s Vault", null, null).getName(), "typografischer Apostroph, ohne Akzent");

        String text = """
                Commander
                1 Valki, God of Lies // Tibalt, Cosmic Impostor (KHM) 114
                Deck
                1 Fire // Ice
                1 Bonecrusher Giant // Stomp
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals("Valki, God of Lies", r.commanders().get(0).name());
        String v2 = r.toText();
        assertTrue(v2.contains("1 Bonecrusher Giant ("), v2);
        assertTrue(v2.contains("1 Fire // Ice ("), v2);
        TextDeckParser.Result again = TextDeckParser.parse(v2, null, null);
        assertTrue(again.unknown().isEmpty());
        assertEquals(v2, again.toText());
    }

    /** Die Vorschau serialisiert Resolved als JSON; der Forge-Druck darf nie mit hinaus. */
    @Test
    void resolvedSerializesWithoutForgeCard() throws Exception {
        TextDeckParser.Result r = TextDeckParser.parse("Commander\n1 Atraxa, Praetors' Voice (CM2) 10\nDeck\n1 Sol Ring (C21) 263\n", null, null);
        assertNotNull(r.main().get(0).card());
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("{\"count\":1,\"name\":\"Sol Ring\",\"set\":\"C21\",\"number\":\"263\",\"commander\":false}", om.writeValueAsString(r.main().get(0)));
        assertEquals("[{\"count\":1,\"name\":\"Atraxa, Praetors' Voice\",\"set\":\"CM2\",\"number\":\"10\",\"commander\":true}]",
                om.writeValueAsString(r.commanders()));
    }

    @Test
    void issuesCarryRawLineNumbers() {
        String text = """
                Commander
                1 Atraxa, Praetors' Voice (CM2) 10

                Deck
                1 Sol Ring
                1 Totally Fake Card Name
                1 Another Fake // Card
                """;
        TextDeckParser.Result r = TextDeckParser.parse(text, null, null);
        assertEquals(List.of("1 Totally Fake Card Name", "1 Another Fake // Card"), r.unknown());
        assertEquals(2, r.issues().size(), r.issues().toString());
        TextDeckParser.Issue fake = r.issues().get(0);
        assertEquals(6, fake.line());
        assertEquals("unknown", fake.kind());
        assertEquals(1, fake.count());
        assertNull(fake.suggestion(), "parse() schlaegt nie etwas vor");
        assertEquals(7, r.issues().get(1).line());
        assertTrue(r.issues().stream().allMatch(i -> "unknown".equals(i.kind())), "die Issue-Art 'unfinished' gibt es nicht mehr");
        assertEquals("artifact", r.typeOf("Sol Ring"));
        assertEquals("creature", r.typeOf("Atraxa, Praetors' Voice"));
    }

    @Test
    void suggestionsOnlyForNearNames() {
        String text = """
                1 Sol Rnig
                1 Totally Fake Card Name
                1 Arcane Signet
                """;
        TextDeckParser.Result r = CardNameSuggester.withSuggestions(TextDeckParser.parse(text, null, null));
        assertEquals(List.of("1 Sol Rnig", "1 Totally Fake Card Name"), r.unknown());
        assertEquals("Sol Ring", r.issues().get(0).suggestion());
        assertEquals(1, r.issues().get(0).line());
        assertNull(r.issues().get(1).suggestion(), r.issues().get(1).toString());
        assertEquals(2, r.issues().get(1).line());
        assertEquals("Lightning Bolt", CardNameSuggester.suggest("Lightnign Bolt"));
        assertEquals("Sol Ring", CardNameSuggester.suggest("sol ring"));
    }

    @Test
    void suggestionsAreBoundedInTime() {
        StringBuilder sb = new StringBuilder("Deck\n");
        for (int i = 0; i < 300; i++) {
            sb.append("1 Qwzx Unknown Thing ").append(i).append('\n');
        }
        TextDeckParser.checkSize(sb.toString());
        CardNameSuggester.suggest("warmup"); // Namensliste laden (einmalig)
        long t0 = System.nanoTime();
        TextDeckParser.Result r = CardNameSuggester.withSuggestions(TextDeckParser.parse(sb.toString(), null, null));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(300, r.issues().size());
        assertTrue(ms < 20_000, "zu langsam: " + ms + " ms");
        assertTrue(r.issues().stream().allMatch(i -> i.suggestion() == null));
        String tooLong = "1 Sol Ring\n".repeat(TextDeckParser.MAX_LINES + 1);
        assertThrows(IllegalArgumentException.class, () -> TextDeckParser.checkSize(tooLong));
    }

    /** DeckLoader: Commander-Pruefung ueber Forge, unbekannte Karten machen das Deck ungueltig, Text ist v2. */
    @Test
    void loaderValidatesAndNormalizes() {
        LoadedDeck bad = DeckLoader.fromText("Commander\n1 Atraxa, Praetors' Voice\nDeck\n1 Sol Ring\n1 Totally Fake Card Name\n", "Klein", "test");
        assertFalse(bad.valid());
        assertTrue(bad.validationErrors().startsWith("Unbekannte Karten: 1 Totally Fake Card Name"), bad.validationErrors());
        assertTrue(bad.validationErrors().contains("99"), "Groessen-Problem von Forge: " + bad.validationErrors());
        assertEquals(List.of("Atraxa, Praetors' Voice"), bad.commanders());
        assertEquals(1, bad.mainCount());
        assertTrue(bad.text().startsWith("Commander\n1 Atraxa, Praetors' Voice ("), bad.text());

        LoadedDeck noCmd = DeckLoader.fromDckText("1 Sol Ring\n30 Island\n", "Ohne", "test");
        assertFalse(noCmd.valid());
        assertEquals("Kein Commander gewählt", noCmd.validationErrors());

        // 3 Einzelkarten + 96 Island (Atraxa ist WUBG)
        String full = "Commander\n1 Atraxa, Praetors' Voice\nDeck\n1 Sol Ring\n1 Arcane Signet\n1 Command Tower\n96 Island\n";
        LoadedDeck ok = DeckLoader.fromText(full, "Voll", "test");
        assertTrue(ok.valid(), ok.validationErrors());
        assertEquals(99, ok.mainCount());
        assertEquals(99, ok.newDeck().getMain().countAll(), "newDeck() = frische Kopie");
        assertEquals(1, ok.newDeck().getCommanders().size());

        LoadedDeck offColor = DeckLoader.fromText("Commander\n1 Atraxa, Praetors' Voice\nDeck\n1 Lightning Bolt\n98 Island\n", "Rot", "test");
        assertFalse(offColor.valid());
        assertTrue(offColor.validationErrors().contains("Lightning Bolt"), offColor.validationErrors());

        // Commander-Bannliste (Forge: Commander-Praedikat) und Kopienzahl
        LoadedDeck banned = DeckLoader.fromText("Commander\n1 Atraxa, Praetors' Voice\nDeck\n1 Black Lotus\n98 Island\n", "Bann", "test");
        assertFalse(banned.valid());
        assertTrue(banned.validationErrors().contains("Black Lotus"), banned.validationErrors());
        LoadedDeck twice = DeckLoader.fromText("Commander\n1 Atraxa, Praetors' Voice\nDeck\n2 Sol Ring\n97 Island\n", "Doppelt", "test");
        assertFalse(twice.valid());
        assertTrue(twice.validationErrors().contains("Sol Ring"), twice.validationErrors());
    }
}
