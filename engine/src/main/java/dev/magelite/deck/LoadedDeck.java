package dev.magelite.deck;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.game.GameException;

import java.util.List;

/**
 * Geparstes Deck. {@link #newDeck()} erzeugt pro Spiel frische Kartenobjekte (Karten werden an ein Spiel gebunden).
 */
public record LoadedDeck(
        String name,
        String source,
        DeckCardLists lists,
        List<String> commanders,
        int mainCount,
        boolean valid,
        String validationErrors,
        String importErrors
) {

    public Deck newDeck() throws GameException {
        Deck deck = Deck.load(lists, true, false);
        deck.setName(name);
        return deck;
    }

    public static List<String> commanderNames(Deck deck) {
        return deck.getSideboard().stream().map(Card::getName).sorted().toList();
    }
}
