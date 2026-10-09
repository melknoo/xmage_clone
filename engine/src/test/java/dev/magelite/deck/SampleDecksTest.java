package dev.magelite.deck;

import dev.magelite.ForgeTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Die 70 mitgelieferten Commander-Decks (Decktext v2 im Classpath) gegen Forges Karten-DB. */
class SampleDecksTest {

    /** Alle Decks im Katalog. */
    static final int SAMPLES = 70;
    /**
     * Davon Commander-konform nach Forge inkl. Bannliste: 67/70 (wie der XMage-Stand). Ohne Bannlistenpruefung waeren es
     * 69/70 (Forge-POC). Aendert sich die Zahl, hat sich Forge oder ein Deck geaendert - dann bewusst nachziehen.
     */
    static final int VALID = 67;
    /** Ungueltig: Trade Secrets (verboten), Dockside Extortionist (verboten), Mossfire Valley doppelt. */
    static final Set<String> INVALID_IDS = Set.of(
            "Commander 2011/Political Puppets.dck",
            "Commander 2019/Mystic Intellect.dck",
            "Kamigawa Neon Dynasty (2022)/Upgrades Unleashed (RG).dck");

    @BeforeAll
    static void boot() {
        ForgeTestSupport.boot();
    }

    @Test
    void indexListsAllSamplesAndIdsAreStable() throws IOException {
        List<String> ids = SampleDeckCatalog.index();
        assertEquals(SAMPLES, ids.size(), "INDEX (Gradle-Task sampleIndex) muss alle Decks listen");
        assertEquals(SAMPLES, new HashSet<>(ids).size(), "ids eindeutig");
        assertTrue(ids.contains("Commander 2014/Peer Through Time.dck"), "ids bleiben wie zu XMage-Zeiten");
        assertTrue(ids.contains("Tasigur BGU.dck"));
        assertTrue(ids.stream().noneMatch(s -> s.contains("\\") || s.startsWith("/")));
    }

    @Test
    void catalogListsSamplesWithColors() {
        SampleDeckCatalog catalog = new SampleDeckCatalog();
        List<SampleDeckCatalog.Entry> all = catalog.list();
        assertEquals(SAMPLES, all.size());
        SampleDeckCatalog.Entry peer = catalog.find("Commander 2014/Peer Through Time.dck").orElseThrow();
        assertEquals("Peer Through Time", peer.name());
        assertEquals("Commander 2014", peer.group());
        assertEquals(List.of("Teferi, Temporal Archmage"), peer.commanders());
        assertEquals("U", peer.colors());
        assertEquals("C14", peer.commanderSet());
        assertEquals("19", peer.commanderNum());
        assertEquals(100, peer.cards());
        SampleDeckCatalog.Entry tasigur = catalog.find("Tasigur BGU.dck").orElseThrow();
        assertEquals("", tasigur.group());
        assertEquals("Tasigur BGU", tasigur.name(), "ohne NAME: gilt der Dateiname");
        assertEquals("BGU", sorted(tasigur.colors()), "Tasigur: Farben " + tasigur.colors());
        assertTrue(catalog.find("gibt es nicht.dck").isEmpty());
        assertTrue(all.stream().allMatch(e -> e.commanders().size() >= 1 && e.commanders().size() <= 2), "1-2 Commander je Deck");
        List<String> wrong = all.stream().filter(e -> e.cards() != 100).map(e -> e.id() + "=" + e.cards()).toList();
        assertTrue(wrong.isEmpty(), "100 Karten je Deck: " + wrong);
    }

    private static String sorted(String s) {
        char[] c = s.toCharArray();
        java.util.Arrays.sort(c);
        return new String(c);
    }

    @Test
    void allSamplesParseWithoutUnknownCards() {
        SampleDeckCatalog catalog = new SampleDeckCatalog();
        List<String> problems = new ArrayList<>();
        int valid = 0;
        Set<String> invalid = new HashSet<>();
        for (SampleDeckCatalog.Entry e : catalog.list()) {
            String text = catalog.text(e.id());
            TextDeckParser.Result r = TextDeckParser.parse(text, e.name(), null);
            if (!r.unknown().isEmpty()) {
                problems.add(e.id() + ": unbekannt " + r.unknown());
            }
            if (r.commanders().isEmpty()) {
                problems.add(e.id() + ": kein Commander");
            }
            LoadedDeck d = DeckLoader.fromText(text, e.name(), "sample:" + e.id());
            assertEquals(e.commanders().size(), d.commanders().size(), e.id());
            assertEquals(100 - e.commanders().size(), d.mainCount(), e.id());
            if (d.valid()) {
                valid++;
            } else {
                invalid.add(e.id());
            }
        }
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(VALID, valid, "gueltige Samples; ungueltig: " + invalid);
        assertEquals(INVALID_IDS, invalid);
    }

    @Test
    void sampleTextIsStableUnderReparse() {
        SampleDeckCatalog catalog = new SampleDeckCatalog();
        for (SampleDeckCatalog.Entry e : catalog.list()) {
            String once = TextDeckParser.parse(catalog.text(e.id()), e.name(), null).toText();
            String twice = TextDeckParser.parse(once, e.name(), null).toText();
            assertEquals(once, twice, e.id());
            assertFalse(once.contains("NAME:"));
        }
    }

    @Test
    void rejectsPathTricks() {
        SampleDeckCatalog catalog = new SampleDeckCatalog();
        assertThrows(IllegalArgumentException.class, () -> catalog.text("../magelite-version.properties"));
        assertThrows(IllegalArgumentException.class, () -> catalog.text("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> catalog.text("nicht vorhanden.dck"));
    }
}
