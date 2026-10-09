package dev.magelite.game;

/**
 * Forge-Spielereignisse ({@code game.subscribeToEvents}) → FX-Ereignisse fuer die UI ({@link GameHost#onFx}) und
 * Statistik ({@code StatsSink}). Ersetzt XMages FxWatcher/StatsWatcher.
 * <p>
 * Handler laufen synchron auf dem Spiel-Thread; sie beobachten nur (nie fragen, blockieren oder das Spiel aendern).
 */
final class ForgeEvents {

    private final GameHost host;

    ForgeEvents(GameHost host) {
        this.host = host;
    }
}
