package dev.magelite.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LobbyHelpersTest {

    @Test
    void defaultTableNameIsGermanGenitive() {
        assertEquals("Annas Tisch", TableManager.defaultName("Anna"));
        assertEquals("Ilias' Tisch", TableManager.defaultName("Ilias"));
        assertEquals("Max' Tisch", TableManager.defaultName("Max"));
        assertEquals("Fritz' Tisch", TableManager.defaultName("Fritz"));
        assertEquals("Grieß' Tisch", TableManager.defaultName("Grieß"));
        assertEquals("Neuer Tisch", TableManager.defaultName("  "));
    }

    @Test
    void replacementCauseFromRuleText() {
        assertEquals("Karte ziehen", ReplacementAssist.cause(
                "Dredge 2 <i>(If you would draw a card, you may mill 2 cards instead. If you do, return this card from your graveyard to your hand.)</i>"));
        assertEquals("Sterben", ReplacementAssist.cause("If a creature an opponent controls would die, exile it instead."));
        assertEquals("Schaden", ReplacementAssist.cause("If a source would deal damage to you, prevent 1 of that damage."));
        assertNull(ReplacementAssist.cause("Flying"));
    }
}
