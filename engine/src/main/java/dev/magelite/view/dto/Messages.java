package dev.magelite.view.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weitere Server->Client-Nachrichten (Feld {@code t} = Typ).
 */
public final class Messages {

    private Messages() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Hello(String t, int protocol, UUID gameId, UUID myPlayerId, List<Seat> seats, String tempo) {
        public Hello(UUID gameId, UUID myPlayerId, List<Seat> seats, String tempo) {
            this("hello", 1, gameId, myPlayerId, seats, tempo);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Seat(UUID playerId, String name, boolean human, String deckName, List<String> commanders) {
    }

    public record PromptClosed(String t, long id) {
        public PromptClosed(long id) {
            this("promptClosed", id);
        }
    }

    /** {@code active}: Name des aktiven Spielers (Gruppierung im Verlauf) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LogEntry(long ts, int turn, String active, String kind, List<Map<String, Object>> rich) {
    }

    public record Log(String t, List<LogEntry> entries) {
        public Log(List<LogEntry> entries) {
            this("log", entries);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(String t, UUID thinking, boolean autoPassed, String waitingFor) {
        public Status(UUID thinking, boolean autoPassed, String waitingFor) {
            this("status", thinking, autoPassed, waitingFor);
        }
    }

    /**
     * Herzschlag (1/s): was die Engine gerade tut. {@code mode}: you | bot | engine | idle | stuck.
     * {@code cpu}: CPU-Last der Engine-Threads in % eines Kerns; {@code idleMs}: Zeit seit der letzten Spielaenderung;
     * {@code recovered}: Zahl automatisch neu zugestellter Antworten (XMage-Race).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Activity(String t, String mode, String who, int cpu, long idleMs, int recovered) {
        public Activity(String mode, String who, int cpu, long idleMs, int recovered) {
            this("activity", mode, who, cpu, idleMs, recovered);
        }
    }

    public record Toast(String t, String level, List<Map<String, Object>> rich) {
        public Toast(String level, List<Map<String, Object>> rich) {
            this("toast", level, rich);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Placement(UUID playerId, String name, int place, boolean human, int life, Integer eliminatedTurn, int mulligans) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GameOver(String t, UUID winnerId, String result, List<Placement> placements, int turns, long durationMs,
                           Object reward, String error) {
        public GameOver(UUID winnerId, String result, List<Placement> placements, int turns, long durationMs, Object reward, String error) {
            this("gameOver", winnerId, result, placements, turns, durationMs, reward, error);
        }
    }

    public record Error(String t, String message, boolean fatal) {
        public Error(String message, boolean fatal) {
            this("error", message, fatal);
        }
    }
}
