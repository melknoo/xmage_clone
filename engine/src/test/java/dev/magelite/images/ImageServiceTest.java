package dev.magelite.images;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Token-Namensabgleich fuer den exakten Scryfall-Druck (/cards/&lt;token-set&gt;/&lt;num&gt;) und die Nummern-Umschrift. */
class ImageServiceTest {

    private static JsonNode card(String json) throws Exception {
        return Json.MAPPER.readTree(json);
    }

    @Test
    void tokenNameMatchesPlainAndTwoFaced() throws Exception {
        assertTrue(ImageService.tokenNameMatches("Soldier", card("{\"name\":\"Soldier\"}")));
        assertTrue(ImageService.tokenNameMatches("soldier", card("{\"name\":\"Soldier\"}")));
        assertTrue(ImageService.tokenNameMatches("Soldier Token", card("{\"name\":\"Soldier\"}")));
        // zweiseitige Tokens: Name "A // B", jede Seite zaehlt
        assertTrue(ImageService.tokenNameMatches("Human Soldier", card("{\"name\":\"Human Soldier // Spirit\"}")));
        assertTrue(ImageService.tokenNameMatches("Spirit", card("{\"name\":\"Human Soldier // Spirit\"}")));
        assertTrue(ImageService.tokenNameMatches("Spirit", card("{\"name\":\"X\",\"card_faces\":[{\"name\":\"Y\"},{\"name\":\"Spirit\"}]}")));
        // Forge fuehrt den vollen Untertyp ("Phyrexian Wurm Token"), Scryfall nur den letzten ("Wurm")
        assertTrue(ImageService.tokenNameMatches("Phyrexian Wurm Token", card("{\"name\":\"Wurm\"}")));
        assertTrue(ImageService.tokenNameMatches("Phyrexian Horror Token", card("{\"name\":\"Horror\"}")));
        // Akzente/Gross-Klein egal
        assertTrue(ImageService.tokenNameMatches("Dack Fayden", card("{\"name\":\"Däck Fayden\"}")));
    }

    @Test
    void tokenNameMismatchIsRejected() throws Exception {
        assertFalse(ImageService.tokenNameMatches("Soldier", card("{\"name\":\"Zombie\"}")));
        assertFalse(ImageService.tokenNameMatches("Soldier", card("{\"name\":\"Soldier Ant\"}")));
        assertFalse(ImageService.tokenNameMatches("Wurm", card("{\"name\":\"Phyrexian Wurm\"}")));
        assertFalse(ImageService.tokenNameMatches("Swarm Token", card("{\"name\":\"Warm\"}")), "Suffix nur an Wortgrenze");
        assertFalse(ImageService.tokenNameMatches("", card("{\"name\":\"Soldier\"}")));
        assertFalse(ImageService.tokenNameMatches("Soldier", card("{}")));
    }

    @Test
    void numberTransformKeepsXmageAliases() {
        assertEquals("123", ImageService.transformNumber("123"));
        assertEquals("12★", ImageService.transformNumber("12*"));
        assertEquals("12†", ImageService.transformNumber("12+"));
        assertEquals("12Φ", ImageService.transformNumber("12Ph"));
    }

    @Test
    void cleanTokenNameStripsTokenSuffix() {
        assertEquals("Soldier", ImageService.cleanTokenName("Soldier Token"));
        assertEquals("Soldier", ImageService.cleanTokenName("Soldier"));
        assertEquals("Elf Warrior", ImageService.cleanTokenName("Elf Warrior.c14"));
    }
}
