package dev.magelite.game;

import org.apache.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laufende/zuletzt beendete Spiele. Es laeuft immer hoechstens ein Spiel.
 */
public final class GameRegistry {

    private static final Logger LOG = Logger.getLogger(GameRegistry.class);

    private final Map<UUID, GameHost> games = new ConcurrentHashMap<>();
    /** zuletzt gestartetes Spiel; aeltere werden nur noch beendet */
    private volatile GameHost current;

    private volatile java.util.function.BiFunction<GameHost, dev.magelite.view.dto.Messages.GameOver, Object> rewardHook;

    public void setRewardHook(java.util.function.BiFunction<GameHost, dev.magelite.view.dto.Messages.GameOver, Object> hook) {
        this.rewardHook = hook;
    }

    public synchronized GameHost start(GameSetup setup, java.util.function.Consumer<GameHost> onFinished) throws Exception {
        // altes Spiel beenden (es gibt nur einen Tisch)
        for (GameHost old : games.values()) {
            if (old.isRunning()) {
                LOG.info("Beende laufendes Spiel " + old.getId());
                old.abort();
            }
        }
        games.values().removeIf(g -> !g.isRunning());
        GameHost host = GameHost.create(setup);
        host.setOnFinished(onFinished);
        host.setRewardHook(rewardHook);
        games.put(host.getId(), host);
        current = host;
        host.start();
        return host;
    }

    public Optional<GameHost> get(UUID id) {
        return Optional.ofNullable(games.get(id));
    }

    public Optional<GameHost> current() {
        GameHost c = current;
        return c != null && c.isRunning() ? Optional.of(c) : Optional.empty();
    }

    public void shutdown() {
        for (GameHost g : games.values()) {
            if (g.isRunning()) {
                g.shutdownNow();
            }
        }
    }
}
