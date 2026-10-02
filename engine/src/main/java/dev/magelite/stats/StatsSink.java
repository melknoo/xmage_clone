package dev.magelite.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sammelt Spiel-Statistiken des menschlichen Spielers (pro Spiel, threadsicher).
 * Wird vom {@link StatsWatcher} gefuellt; der Watcher selbst darf keinen Zustand halten,
 * weil XMage Watcher fuer AI-Simulationen kopiert.
 */
public final class StatsSink {

    private static final Map<UUID, StatsSink> SINKS = new ConcurrentHashMap<>();

    public static final class CardStat {
        public boolean opening;
        public int drawn;
        public int cast;
        public Integer firstCastTurn;
        public int played;
    }

    private final UUID humanId;
    private final Map<String, CardStat> cards = new LinkedHashMap<>();
    private boolean openingRecorded;
    private int humanTurns;
    private int commanderCasts;
    private Integer firstCommanderTurn;
    private int damageDealt;
    private int commanderDamageDealt;
    private int landsPlayed;
    private int spellsCast;
    private int cardsDrawn;
    private int maxLife;

    private StatsSink(UUID humanId) {
        this.humanId = humanId;
    }

    public static StatsSink register(UUID gameId, UUID humanId) {
        StatsSink s = new StatsSink(humanId);
        SINKS.put(gameId, s);
        return s;
    }

    public static StatsSink of(UUID gameId) {
        return SINKS.get(gameId);
    }

    public static void unregister(UUID gameId) {
        SINKS.remove(gameId);
    }

    public UUID humanId() {
        return humanId;
    }

    private CardStat card(String name) {
        return cards.computeIfAbsent(name, n -> new CardStat());
    }

    public synchronized void opening(Iterable<String> names) {
        if (openingRecorded) {
            return;
        }
        openingRecorded = true;
        for (String n : names) {
            card(n).opening = true;
        }
    }

    public synchronized boolean openingRecorded() {
        return openingRecorded;
    }

    public synchronized void humanTurn() {
        humanTurns++;
    }

    public synchronized void drew(String name) {
        cardsDrawn++;
        card(name).drawn++;
    }

    public synchronized void cast(String name, int turn, boolean commander) {
        spellsCast++;
        CardStat c = card(name);
        c.cast++;
        if (c.firstCastTurn == null) {
            c.firstCastTurn = turn;
        }
        if (commander) {
            commanderCasts++;
            if (firstCommanderTurn == null) {
                firstCommanderTurn = turn;
            }
        }
    }

    public synchronized void landPlayed(String name) {
        landsPlayed++;
        card(name).played++;
    }

    public synchronized void damage(int amount, boolean byCommander) {
        damageDealt += amount;
        if (byCommander) {
            commanderDamageDealt += amount;
        }
    }

    public synchronized void life(int life) {
        maxLife = Math.max(maxLife, life);
    }

    public synchronized Map<String, CardStat> cards() {
        return new LinkedHashMap<>(cards);
    }

    public synchronized int humanTurns() {
        return humanTurns;
    }

    public synchronized int commanderCasts() {
        return commanderCasts;
    }

    public synchronized Integer firstCommanderTurn() {
        return firstCommanderTurn;
    }

    public synchronized int damageDealt() {
        return damageDealt;
    }

    public synchronized int commanderDamageDealt() {
        return commanderDamageDealt;
    }

    public synchronized int landsPlayed() {
        return landsPlayed;
    }

    public synchronized int spellsCast() {
        return spellsCast;
    }

    public synchronized int cardsDrawn() {
        return cardsDrawn;
    }
}
