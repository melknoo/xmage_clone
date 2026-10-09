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
    void mulliganQuestionInGerman() {
        assertEquals("Daxos of Meletis beginnt, du bist als 4. dran.\nStarthand behalten?",
                PromptBridge.mulliganText("Daxos of Meletis is going first.\nDu, you are going 4th.\n\nDo you want to keep your hand?"));
        assertEquals("Du beginnst.\nStarthand behalten?", PromptBridge.mulliganText("Du, you are going first!\n\nDo you want to keep your hand?"));
        assertEquals("Starthand behalten?", PromptBridge.mulliganText("Do you want to keep your hand?"));
        assertEquals("Something else", PromptBridge.mulliganText("Something else"));
    }

    @Test
    void manaPromptOnOneLine() {
        assertEquals("Force Spike – Mana zahlen: {1}", PromptBridge.manaText("Force Spike (222)\nPay Mana Cost: {1}"));
        assertEquals("Mana zahlen: {2}{G}", PromptBridge.manaText("Pay Mana Cost: {2}{G}"));
    }

    @Test
    void combatPromptsInGerman() {
        assertEquals("Angreifer wählen: Kreaturen anklicken, dann bestätigen.",
                PromptBridge.combatText("Select creatures to attack Ezuri or select player/card you wish to attack."));
        assertEquals("Blocker wählen: eigene Kreatur anklicken, dann den Angreifer.",
                PromptBridge.combatText("Select another attacker to declare blockers for."));
        assertEquals("Grizzly Bears must block if able.", PromptBridge.combatText("Grizzly Bears must block if able."));
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
