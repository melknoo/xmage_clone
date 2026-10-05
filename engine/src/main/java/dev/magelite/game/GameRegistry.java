package dev.magelite.game;

import org.apache.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laufende/zuletzt beendete Spiele. Jedes Spiel gehoert einem Nutzer ({@link GameSetup#userId()}). Ein Nutzer
 * hat hoechstens ein laufendes Spiel; startet er ein neues, wird sein altes beendet. Insgesamt laufen hoechstens
 * {@code maxGames} Spiele (lokal 1) - ist die Grenze erreicht, wirft {@link #start} eine {@link BusyException}.
 */
public final class GameRegistry {

    private static final Logger LOG = Logger.getLogger(GameRegistry.class);

    /** Kein freier Tisch: ein anderer Nutzer spielt gerade. */
    public static final class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }

    private final Map<UUID, GameHost> games = new ConcurrentHashMap<>();
    private final int maxGames;

    private volatile java.util.function.BiFunction<GameHost, dev.magelite.view.dto.Messages.GameOver, Object> rewardHook;

    public GameRegistry() {
        this(1);
    }

    public GameRegistry(int maxGames) {
        this.maxGames = Math.max(1, maxGames);
    }

    public void setRewardHook(java.util.function.BiFunction<GameHost, dev.magelite.view.dto.Messages.GameOver, Object> hook) {
        this.rewardHook = hook;
    }

    public GameHost start(GameSetup setup, java.util.function.Consumer<GameHost> onFinished) throws Exception {
        return start(setup, onFinished, null);
    }

    /** @param beforeStart laeuft nach dem Aufbau, vor {@code game.start()} (z.B. Test-Szenario), darf null sein */
    public synchronized GameHost start(GameSetup setup, java.util.function.Consumer<GameHost> onFinished,
                                       java.util.function.Consumer<GameHost> beforeStart) throws Exception {
        games.values().removeIf(g -> !g.isRunning());
        // eigenes altes Spiel beenden (ein Tisch pro Nutzer); es laeuft asynchron aus und zaehlt nicht mehr
        for (var it = games.values().iterator(); it.hasNext(); ) {
            GameHost old = it.next();
            if (old.getSetup().userId() == setup.userId()) {
                LOG.info("Beende laufendes Spiel " + old.getId());
                old.abort();
                it.remove();
            }
        }
        if (games.size() >= maxGames) {
            String other = games.values().stream().map(g -> g.getSetup().humanName()).findFirst().orElse("jemand");
            throw new BusyException("Gerade spielt " + other + " - bitte warten, bis das Spiel vorbei ist");
        }
        GameHost host = GameHost.create(setup);
        host.setOnFinished(onFinished);
        host.setRewardHook(rewardHook);
        games.put(host.getId(), host);
        if (beforeStart != null) {
            beforeStart.accept(host);
        }
        host.start();
        return host;
    }

    public Optional<GameHost> get(UUID id) {
        return Optional.ofNullable(games.get(id));
    }

    /** Laufendes Spiel eines Nutzers. */
    public Optional<GameHost> currentOf(long userId) {
        return games.values().stream().filter(g -> g.isRunning() && g.getSetup().userId() == userId).findFirst();
    }

    /** Anzahl laufender Spiele. */
    public int running() {
        return (int) games.values().stream().filter(GameHost::isRunning).count();
    }

    /** Laufende Spiele. */
    public java.util.List<GameHost> runningGames() {
        return games.values().stream().filter(GameHost::isRunning).toList();
    }

    /** Beendet das laufende Spiel eines Nutzers (z.B. nach Entzug der Einladung). */
    public void abortOf(long userId) {
        games.values().stream().filter(g -> g.isRunning() && g.getSetup().userId() == userId).forEach(GameHost::abort);
    }

    public void shutdown() {
        for (GameHost g : games.values()) {
            if (g.isRunning()) {
                g.shutdownNow();
            }
        }
    }
}
