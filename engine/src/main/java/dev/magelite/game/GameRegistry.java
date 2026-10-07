package dev.magelite.game;

import org.apache.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Laufende/zuletzt beendete Spiele. Jedes Spiel hat einen oder mehrere menschliche Sitze ({@link GameSetup#humans()}).
 * Ein Nutzer sitzt hoechstens in einem laufenden Spiel; startet er ein neues, wird sein altes beendet. Insgesamt
 * laufen hoechstens {@code maxGames} Spiele (lokal 1) - ist die Grenze erreicht, wirft {@link #start} eine
 * {@link BusyException}.
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

    private volatile GameHost.RewardHook rewardHook;

    public GameRegistry() {
        this(1);
    }

    public GameRegistry(int maxGames) {
        this.maxGames = Math.max(1, maxGames);
    }

    public void setRewardHook(GameHost.RewardHook hook) {
        this.rewardHook = hook;
    }

    public GameHost start(GameSetup setup, java.util.function.Consumer<GameHost> onFinished) throws Exception {
        return start(setup, onFinished, null);
    }

    /** @param beforeStart laeuft nach dem Aufbau, vor {@code game.start()} (z.B. Test-Szenario), darf null sein */
    public synchronized GameHost start(GameSetup setup, java.util.function.Consumer<GameHost> onFinished,
                                       java.util.function.Consumer<GameHost> beforeStart) throws Exception {
        games.values().removeIf(g -> !g.isRunning());
        // alte Spiele der beteiligten Nutzer beenden (ein Tisch pro Nutzer); sie laufen asynchron aus und zaehlen nicht mehr
        for (var it = games.values().iterator(); it.hasNext(); ) {
            GameHost old = it.next();
            boolean overlap = setup.humans().stream().anyMatch(h -> old.getSetup().hasUser(h.userId()));
            if (overlap) {
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

    /** Laufendes Spiel, in dem der Nutzer noch mitspielt (nicht aufgegeben hat); fuer den Reconnect. */
    public Optional<GameHost> currentOf(long userId) {
        return games.values().stream()
                .filter(g -> g.isRunning() && g.seatOf(userId).map(s -> !s.conceded()).orElse(false))
                .findFirst();
    }

    /** Obergrenze gleichzeitiger Spiele ({@code --max-games}). */
    public int maxGames() {
        return maxGames;
    }

    /** Anzahl laufender Spiele. */
    public int running() {
        return (int) games.values().stream().filter(GameHost::isRunning).count();
    }

    /** Laufende Spiele. */
    public List<GameHost> runningGames() {
        return games.values().stream().filter(GameHost::isRunning).toList();
    }

    /** Der Nutzer gibt in seinem laufenden Spiel auf (z.B. nach Entzug der Einladung). */
    public void abortOf(long userId) {
        games.values().stream().filter(g -> g.isRunning() && g.getSetup().hasUser(userId))
                .forEach(g -> g.seatOf(userId).ifPresent(g::leave));
    }

    public void shutdown() {
        for (GameHost g : games.values()) {
            if (g.isRunning()) {
                g.shutdownNow();
            }
        }
    }
}
