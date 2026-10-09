package dev.magelite.view;

import dev.magelite.ForgeTestSupport;
import dev.magelite.deck.CardLookup;
import forge.StaticData;
import forge.game.card.Card;
import forge.item.PaperCard;
import forge.item.PaperToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bild-Druck (Scryfall-Set + Nummer) fuer Karten und Tokens, Token-Set aus Forges {@code TokensCode}. */
class ForgeViewMapperPrintTest {

    @BeforeAll
    static void boot() {
        ForgeTestSupport.boot();
    }

    @Test
    void tokenSetIsTokensCodeOfEdition() {
        assertEquals("TC20", ForgeViewMapper.tokenSet("C20"));
        assertEquals("TC14", ForgeViewMapper.tokenSet("C14"));
        // Sets mit Tokens im Set selbst (TokensCode = Set-Code)
        assertEquals("SLD", ForgeViewMapper.tokenSet("SLD"));
        assertNull(ForgeViewMapper.tokenSet(null));
        assertNull(ForgeViewMapper.tokenSet(""));
        assertNull(ForgeViewMapper.tokenSet("???"));
        assertNull(ForgeViewMapper.tokenSet("GIBTESNICHT"));
    }

    @Test
    void tokenPrintCarriesTokenSetAndNumber() {
        // Commander 2014: "6 w_1_1_soldier" im [tokens]-Abschnitt der Edition
        PaperToken pt = StaticData.instance().getAllTokens().getToken("w_1_1_soldier", "C14", 1);
        assertNotNull(pt);
        assertEquals("C14", pt.getEdition());
        assertEquals("6", pt.getCollectorNumber());
        Card c = Card.fromPaperCard(pt, null);
        assertTrue(c.isToken(), "Token-Karte");
        ForgeViewMapper.Print p = ForgeViewMapper.print(c);
        assertTrue(p.token());
        assertEquals("TC14", p.set());
        assertEquals("6", p.num());
    }

    @Test
    void realCardKeepsSetAndNumber() {
        PaperCard sol = CardLookup.resolve("Sol Ring", "C14", null);
        assertNotNull(sol);
        Card c = Card.fromPaperCard(sol, null);
        ForgeViewMapper.Print p = ForgeViewMapper.print(c);
        assertFalse(p.token());
        assertEquals("C14", p.set());
        assertEquals(CardLookup.number(sol), p.num());
        assertFalse(p.num().isBlank());
    }
}
