package dev.magelite.deck;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.List;

/**
 * Geparstes Commander-Deck (Forge). {@link #newDeck()} liefert pro Spiel eine eigene Kopie; die Spiel-Karten erzeugt
 * Forge daraus erst beim Anlegen des Spiels.
 *
 * @param text Decktext (Host-Link, Speichern); null, wenn nicht bekannt
 */
public record LoadedDeck(
        String name,
        String source,
        Deck deck,
        List<String> commanders,
        int mainCount,
        boolean valid,
        String validationErrors,
        String importErrors,
        String text
) {

    public LoadedDeck withText(String text) {
        return new LoadedDeck(name, source, deck, commanders, mainCount, valid, validationErrors, importErrors, text);
    }

    /** Frische Kopie fuer ein Spiel. */
    public Deck newDeck() {
        return new Deck(deck, name);
    }

    public static List<String> commanderNames(Deck deck) {
        if (!deck.has(DeckSection.Commander)) {
            return List.of();
        }
        return deck.get(DeckSection.Commander).toFlatList().stream().map(PaperCard::getName).sorted().toList();
    }
}
