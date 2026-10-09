package dev.magelite.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForgeTextTest {

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
