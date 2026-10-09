package dev.magelite.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForgeTextTest {

    @Test
    void germanQuestionsAndChoices() {
        assertEquals("Ausgelöste Fähigkeit von Forgotten Ancient nutzen? (Whenever a player casts a spell, you may put a +1/+1 counter.)",
                ForgeText.german("Use triggered ability of Forgotten Ancient (52)? (Whenever a player casts a spell, you may put a +1/+1 counter.)"));
        assertEquals("Kitt Kanto, Mayhem Diva in die Kommandozone zurücklegen?",
                ForgeText.german("Kitt Kanto, Mayhem Diva: If a commander is in a graveyard or in exile and that card was put into that zone since the last time state-based actions were checked, you may put it into the command zone."));
        assertEquals("Gemstone Caverns ins Spiel bringen?", ForgeText.german("Put Gemstone Caverns onto the battlefield?"));
        assertEquals("1 Leben zahlen?", ForgeText.german("Pay 1 life?"));
        assertEquals("X für Blaze wählen", ForgeText.german("Choose X for Blaze"));
        assertEquals("Wie viele +1/+1-Marken auf Yusri, Fortune's Flame legen?", ForgeText.german("Put how many +1/+1 counters on Yusri, Fortune's Flame?"));
        assertEquals("Reihenfolge gleichzeitiger Fähigkeiten – zuerst auflösen (1/3)",
                ForgeText.german("Select order for simultaneous abilities – Resolve first (1/3)"));
        assertEquals("Aufräumen: 1 Karte(n) abwerfen (Handlimit 7)",
                ForgeText.german("Cleanup Phase Select 1 card(s) to discard to bring your hand down to the maximum of 7 cards."));
    }

    @Test
    void germanTargetsAndZones() {
        assertEquals("Blaze – beliebiges Ziel wählen", ForgeText.german("Blaze – Select any target"));
        assertEquals("Boros Charm – Ziel wählen: player or planeswalker", ForgeText.german("Boros Charm – Select target player or planeswalker"));
        assertEquals("creature zum Tappen wählen (noch 3)", ForgeText.german("Select a(n) creature to tap (3 left)"));
        assertEquals("Karte aus deiner Bibliothek wählen", ForgeText.german("Select a card from your library"));
        assertEquals("Blick in deine Bibliothek", ForgeText.german("Looking at cards in your library"));
        assertEquals("Blick in die Hand von Inalla", ForgeText.german("Looking at cards in Inalla's hand"));
        assertEquals("Selvala, Explorer Returned – deckt Karten aus der Bibliothek von Okaun auf",
                ForgeText.german("Selvala, Explorer Returned - Revealing cards from Okaun's library"));
        assertEquals("Okaun gewinnt den Münzwurf", ForgeText.german("Okaun wins the flip"));
    }

    @Test
    void germanMoreForgeSentences() {
        assertEquals("Aufräumen:\n1 Karte(n) abwerfen",
                ForgeText.german("Cleanup Phase\nSelect 1 card(s) to discard to bring your hand down to the maximum of 7 cards."));
        assertEquals("2 Leben zahlen?", ForgeText.german("Do you want to pay 2 life?"));
        assertEquals("Clan Defiance – Modus wählen", ForgeText.german("Tester activated Clan Defiance - Choose a mode"));
        assertEquals("Karten wählen, die unter die Bibliothek kommen", ForgeText.german("Select cards to be put on the bottom of your library"));
    }

    @Test
    void germanKeepsUnknownAndOwnTexts() {
        assertEquals("Zauber und Fähigkeiten spielen.", ForgeText.german("Zauber und Fähigkeiten spielen."));
        assertEquals("Something Forge says", ForgeText.german("Something Forge says"));
        assertEquals("Ja", ForgeText.button("Yes"));
        assertEquals("Nein", ForgeText.button("No"));
        assertEquals("Mulligan", ForgeText.button("Mulligan"));
    }

    @Test
    void stripsObjectIds() {
        assertEquals("Okaun played Academy Ruins", ForgeText.clean("Okaun played Academy Ruins (362)"));
        assertEquals("Tavern Scoundrel deals 1 combat damage to Osgir.", ForgeText.clean("Tavern Scoundrel (359) deals 1 combat damage to Osgir."));
        assertEquals("Du assigned Zombie Token and Zombie Token to attack Kaalia.",
                ForgeText.clean("Du assigned Zombie Token (441) and Zombie Token (442) to attack Kaalia."));
    }

    @Test
    void stripsTriggerContext() {
        assertEquals("When Jungle Hollow enters, you gain 1 life.",
                ForgeText.clean("When Jungle Hollow enters, you gain 1 life. [Zone Changer: Jungle Hollow (8)]"));
        assertEquals("Whenever Jeleva attacks, you may cast an instant.",
                ForgeText.clean("Whenever Jeleva attacks, you may cast an instant. [Attacker: Jeleva, Nephalia's Scourge (100)]"));
    }

    @Test
    void keepsOrdinaryText() {
        assertEquals("Guardian of Vitu-Ghazi - Creature 4 / 7", ForgeText.clean("Guardian of Vitu-Ghazi - Creature 4 / 7"));
        assertEquals("Life: Tester 40 > 41", ForgeText.clean("Life: Tester 40 > 41"));
        assertEquals("Bushido 1 (Whenever this blocks or becomes blocked, it gets +1/+1.)",
                ForgeText.clean("Bushido 1 (Whenever this blocks or becomes blocked, it gets +1/+1.)"));
        assertEquals("Choose one [or both]", ForgeText.clean("Choose one [or both]"));
    }
}
