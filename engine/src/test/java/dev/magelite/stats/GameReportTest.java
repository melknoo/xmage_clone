package dev.magelite.stats;

import dev.magelite.view.dto.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameReportTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID BOT = UUID.randomUUID();

    private static GameRecorder.SeatResult seat(UUID player, int humanTurns) {
        return new GameRecorder.SeatResult(1, "Du", player, 5L, "Deck", List.of("Atraxa"), false, humanTurns, Map.of());
    }

    private static GameRecorder.GameResult result(String gameId, String tempo, List<GameRecorder.SeatResult> seats) {
        List<Messages.Placement> pl = List.of(new Messages.Placement(ME, "Du", 1, true, 20, null, 0),
                new Messages.Placement(BOT, "Bot", 2, false, 0, 7, 1));
        return new GameRecorder.GameResult(gameId, tempo, ME, "win", pl, 12, 600_000, null, Map.of(), Map.of(), seats, null);
    }

    @Test
    void validReportPasses() {
        GameRecorder.GameResult r = result(UUID.randomUUID().toString(), "NORMAL", List.of(seat(ME, 6)));
        assertEquals(ME, GameReportRoutes.validate(r).playerId());
    }

    @Test
    void malformedReportsAreRejected() {
        String id = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> GameReportRoutes.validate(result("ml-1", "NORMAL", List.of(seat(ME, 6)))));
        assertThrows(IllegalArgumentException.class, () -> GameReportRoutes.validate(result(id, "NORMAL", List.of(seat(ME, 6), seat(BOT, 6)))));
        assertThrows(IllegalArgumentException.class, () -> GameReportRoutes.validate(result(id, "NORMAL", List.of())));
        assertThrows(IllegalArgumentException.class, () -> GameReportRoutes.validate(result(id, "TURBO", List.of(seat(ME, 6)))));
        assertThrows(IllegalArgumentException.class, () -> GameReportRoutes.validate(result(id, "NORMAL", List.of(seat(UUID.randomUUID(), 6)))));
        assertThrows(IllegalArgumentException.class, () -> GameReportRoutes.validate(result(id, "NORMAL", List.of(seat(ME, 40)))));
    }

    @Test
    void reportedEndTimeIsClamped() {
        long now = System.currentTimeMillis();
        assertTrue(Math.abs(GameRecorder.endTime(null) - now) < 5_000);
        assertTrue(Math.abs(GameRecorder.endTime(now + 86_400_000L) - now) < 5_000, "Zukunft -> jetzt");
        long hourAgo = now - 3_600_000L;
        assertEquals(hourAgo, GameRecorder.endTime(hourAgo));
        long old = GameRecorder.endTime(now - 90L * 86_400_000L);
        assertTrue(now - old <= 30L * 86_400_000L + 5_000 && now - old >= 30L * 86_400_000L - 5_000, "aelter als 30 Tage -> 30 Tage");
    }
}
