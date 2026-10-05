package dev.magelite.game;

import dev.magelite.deck.LoadedDeck;

import java.util.List;

/**
 * Konfiguration eines Spiels: menschlicher Spieler + 3 Bots.
 */
public record GameSetup(
        String humanName,
        LoadedDeck humanDeck,
        List<LoadedDeck> botDecks,
        TempoSettings.Preset tempo,
        /** id des eigenen gespeicherten Decks (fuer Meisterschaft), null bei Sample-Decks */
        Long humanDeckId,
        /** Besitzer des Spiels (Konto); lokal immer 1 */
        long userId
) {

    /** Lokales Spiel (Nutzer 1). */
    public GameSetup(String humanName, LoadedDeck humanDeck, List<LoadedDeck> botDecks, TempoSettings.Preset tempo, Long humanDeckId) {
        this(humanName, humanDeck, botDecks, tempo, humanDeckId, 1L);
    }
}
