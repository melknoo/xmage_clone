package dev.magelite.game;

import dev.magelite.deck.LoadedDeck;

import java.util.ArrayList;
import java.util.List;

/**
 * Konfiguration eines Spiels: Sitze in Tischreihenfolge (Menschen und Bots) und Tempo.
 * Lokal: 1 Mensch (Nutzer 1) + 3 Bots. Online koennen mehrere Menschen am Tisch sitzen.
 */
public record GameSetup(List<SeatSpec> seats, TempoSettings.Preset tempo) {

    /**
     * @param userId Konto des Menschen (lokal 1); bei Bots 0
     * @param deckId id des gespeicherten Decks (fuer Meisterschaft), null bei Sample-Decks
     */
    public record SeatSpec(boolean human, long userId, String name, LoadedDeck deck, Long deckId) {
        public static SeatSpec human(long userId, String name, LoadedDeck deck, Long deckId) {
            return new SeatSpec(true, userId, name, deck, deckId);
        }

        public static SeatSpec bot(LoadedDeck deck) {
            return new SeatSpec(false, 0, null, deck, null);
        }
    }

    public GameSetup {
        seats = List.copyOf(seats);
        if (seats.stream().noneMatch(SeatSpec::human)) {
            throw new IllegalArgumentException("Ein Spiel braucht mindestens einen Menschen");
        }
    }

    /** Lokales Spiel: ein Mensch (Nutzer 1) + Bots. */
    public GameSetup(String humanName, LoadedDeck humanDeck, List<LoadedDeck> botDecks, TempoSettings.Preset tempo, Long humanDeckId) {
        this(humanName, humanDeck, botDecks, tempo, humanDeckId, 1L);
    }

    /** Ein Mensch + Bots. */
    public GameSetup(String humanName, LoadedDeck humanDeck, List<LoadedDeck> botDecks, TempoSettings.Preset tempo, Long humanDeckId, long userId) {
        this(oneHuman(humanName, humanDeck, botDecks, humanDeckId, userId), tempo);
    }

    private static List<SeatSpec> oneHuman(String name, LoadedDeck deck, List<LoadedDeck> bots, Long deckId, long userId) {
        List<SeatSpec> out = new ArrayList<>();
        out.add(SeatSpec.human(userId, name, deck, deckId));
        for (LoadedDeck b : bots) {
            out.add(SeatSpec.bot(b));
        }
        return out;
    }

    /** Erster Mensch (Gastgeber). */
    public SeatSpec firstHuman() {
        return seats.stream().filter(SeatSpec::human).findFirst().orElseThrow();
    }

    public List<SeatSpec> humans() {
        return seats.stream().filter(SeatSpec::human).toList();
    }

    public boolean hasUser(long userId) {
        return seats.stream().anyMatch(s -> s.human() && s.userId() == userId);
    }

    // ---- Kompatibilitaet (erster Mensch)

    public String humanName() {
        return firstHuman().name();
    }

    public LoadedDeck humanDeck() {
        return firstHuman().deck();
    }

    public Long humanDeckId() {
        return firstHuman().deckId();
    }

    public long userId() {
        return firstHuman().userId();
    }

    public List<LoadedDeck> botDecks() {
        return seats.stream().filter(s -> !s.human()).map(SeatSpec::deck).toList();
    }
}
