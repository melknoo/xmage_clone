package dev.magelite.relay;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Beschreibung eines Spiels, das auf dem Rechner eines Gastgebers laeuft (Host-Link). fly schickt sie mit
 * {@code start} (Decks als MageLite-Text v2 im Feld {@code dck}, noch ohne {@code gameId}); die Host-Engine antwortet mit {@code started}
 * (mit {@code gameId}, Spieler-ids, ohne Deck-Text) und wiederholt sie nach einem Reconnect in {@code resume}.
 *
 * @param gameId    id des Spiels (von der Host-Engine vergeben)
 * @param tableId   Tisch auf fly (Korrelation fuer start/started/error)
 * @param hostUserId fly-Konto des Gastgebers
 * @param tempo     {@link dev.magelite.game.TempoSettings.Preset}
 * @param startedAt Startzeit (ms), 0 vor dem Start
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemoteGameSpec(UUID gameId, String tableId, String tableName, long hostUserId, String hostName, String tempo,
                             long startedAt, List<Seat> seats) {

    /**
     * Ein Sitz. {@code dck} nur in {@code start}; {@code playerId}/{@code commanders} erst ab {@code started}.
     * Bots haben {@code userId} 0.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Seat(boolean human, long userId, String name, Long deckId, String deckName, String dck, UUID playerId,
                       List<String> commanders) {
        public Seat withoutDeck() {
            return new Seat(human, userId, name, deckId, deckName, null, playerId, commanders);
        }
    }

    public RemoteGameSpec withGame(UUID gameId, long startedAt, List<Seat> seats) {
        return new RemoteGameSpec(gameId, tableId, tableName, hostUserId, hostName, tempo, startedAt, seats);
    }

    public boolean hasUser(long userId) {
        return seats.stream().anyMatch(s -> s.human() && s.userId() == userId);
    }

    public int humans() {
        return (int) seats.stream().filter(Seat::human).count();
    }
}
