package dev.magelite.spike;

import dev.magelite.boot.CardDbManager;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.MageLiteBot;
import dev.magelite.game.MageLiteMatch;
import dev.magelite.game.TempoSettings;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.events.TableEvent;
import mage.players.Player;
import mage.util.ThreadUtils;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P0a-Spike: 4 Bots spielen headless Commander FFA mit XMage-Sample-Decks.
 * <p>
 * Args: --games=N --turnCap=T --tempo=BLITZ|NORMAL|BEDACHT|MAX --fastOpp=true|false --seed=S
 * --validate (nur Decks pruefen) --verbose (Spiel-Log auf Konsole) --maxMinutes=M
 */
public final class BotSpike {

    private static final AtomicInteger THINK_TIMEOUTS = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        int games = Integer.parseInt(opt.getOrDefault("games", "3"));
        int turnCap = Integer.parseInt(opt.getOrDefault("turnCap", "40"));
        int maxMinutes = Integer.parseInt(opt.getOrDefault("maxMinutes", "20"));
        TempoSettings.Preset preset = TempoSettings.Preset.valueOf(opt.getOrDefault("tempo", "BLITZ").toUpperCase(Locale.ROOT));
        Boolean fastOpp = opt.containsKey("fastOpp") ? Boolean.valueOf(opt.get("fastOpp")) : null;
        boolean verbose = opt.containsKey("verbose");
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.nanoTime())));

        Path vendor = Path.of(System.getProperty("magelite.vendor", "../../vendor/xmage")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, verbose);
        Logger.getLogger("mage.player.ai").addAppender(new ThinkTimeoutCounter());

        long tBoot = System.currentTimeMillis();
        boolean scanned = CardDbManager.ensure(vendor.resolve("db/cards.h2.mv.db"));
        long bootMs = System.currentTimeMillis() - tBoot;
        out("Boot: Karten-DB %d ms (Scan=%s)", bootMs, scanned);

        long tDecks = System.currentTimeMillis();
        List<LoadedDeck> valid = new ArrayList<>();
        int invalid = 0;
        for (Path f : DeckLoader.listDeckFiles(vendor.resolve("sample-decks"))) {
            try {
                LoadedDeck d = DeckLoader.loadFile(f);
                if (d.valid()) {
                    valid.add(d);
                } else {
                    invalid++;
                }
                if (!d.valid() || opt.containsKey("validate")) {
                    out("  %s %-45s main=%d cmd=%s %s %s", d.valid() ? "OK " : "ERR", trim(d.name(), 45), d.mainCount(), d.commanders(),
                            trim(d.validationErrors().replace('\n', ' '), 140), trim(d.importErrors().replace('\n', ' '), 100));
                }
            } catch (Exception e) {
                invalid++;
                out("  EXC %s: %s", f.getFileName(), e);
            }
        }
        out("Decks: %d gueltig, %d ungueltig (%d ms)", valid.size(), invalid, System.currentTimeMillis() - tDecks);
        if (opt.containsKey("validate") || valid.size() < 4) {
            System.exit(0);
        }

        Random rnd = new Random(seed);
        out("Seed=%d tempo=%s fastOpp=%s turnCap=%d", seed, preset, fastOpp == null ? preset.fastOpponentTurns : fastOpp, turnCap);
        List<GameResult> results = new ArrayList<>();
        for (int g = 1; g <= games; g++) {
            List<LoadedDeck> pool = new ArrayList<>(valid);
            Collections.shuffle(pool, rnd);
            GameResult r = runGame(g, pool.subList(0, 4), preset, fastOpp, turnCap, maxMinutes, verbose);
            results.add(r);
            out("Spiel %d: %s | Zuege=%d | %.1f s | %.0f ms/Zug | max %.0f ms/Zug | Think-Timeouts=%d | Heap max %d MB%s",
                    g, r.winner, r.turns, r.durationMs / 1000.0, r.avgTurnMs, r.maxTurnMs, r.thinkTimeouts, r.peakHeapMb,
                    r.error != null ? " | FEHLER " + r.error : "");
        }

        out("=== Zusammenfassung ===");
        double avgDur = results.stream().mapToLong(r -> r.durationMs).average().orElse(0);
        double avgTurns = results.stream().mapToInt(r -> r.turns).average().orElse(0);
        long errors = results.stream().filter(r -> r.error != null).count();
        out("Spiele=%d Fehler=%d Ø Dauer=%.1f s Ø Zuege=%.1f Think-Timeouts gesamt=%d", results.size(), errors, avgDur / 1000, avgTurns, THINK_TIMEOUTS.get());
        System.exit(errors > 0 ? 1 : 0);
    }

    private static GameResult runGame(int nr, List<LoadedDeck> decks, TempoSettings.Preset preset, Boolean fastOpp,
                                      int turnCap, int maxMinutes, boolean verbose) throws Exception {
        TempoSettings tempo = new TempoSettings(preset);
        tempo.setActionDelayMs(0);
        tempo.setCombatDelayMs(0);
        if (fastOpp != null) {
            tempo.setFastOpponentTurns(fastOpp);
        }

        MageLiteMatch match = new MageLiteMatch(MageLiteMatch.defaultOptions("Spike " + nr));
        for (int i = 0; i < 4; i++) {
            LoadedDeck d = decks.get(i);
            MageLiteBot bot = new MageLiteBot("Bot" + (i + 1) + " [" + trim(d.name(), 28) + "]", RangeOfInfluence.ALL, tempo);
            match.addPlayer(bot, d.newDeck());
        }
        match.startMatch();
        match.startGame();
        Game game = match.getGame();

        GameOptions go = GameOptions.getDefault().copy();
        go.rollbackTurnsAllowed = false;
        go.stopOnTurn = turnCap;
        go.stopAtStep = PhaseStep.UPKEEP;
        game.setGameOptions(go);

        int timeoutsBefore = THINK_TIMEOUTS.get();
        GameResult r = new GameResult();
        long[] turnStart = {System.currentTimeMillis()};
        int[] lastTurn = {0};
        List<Long> turnDurations = new ArrayList<>();
        Runtime rt = Runtime.getRuntime();

        game.addTableEventListener(event -> {
            if (event.getEventType() == TableEvent.EventType.UPDATE) {
                int t = game.getTurnNum();
                if (t != lastTurn[0]) {
                    long now = System.currentTimeMillis();
                    if (lastTurn[0] > 0) {
                        turnDurations.add(now - turnStart[0]);
                    }
                    turnStart[0] = now;
                    lastTurn[0] = t;
                    long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    r.peakHeapMb = Math.max(r.peakHeapMb, used);
                    if (verbose) {
                        out("--- Zug %d (aktiv: %s) Heap %d MB", t, playerName(game, game.getActivePlayerId()), used);
                    }
                }
            } else if (verbose && (event.getEventType() == TableEvent.EventType.INFO || event.getEventType() == TableEvent.EventType.STATUS)) {
                out("    %s", stripHtml(event.getMessage()));
            } else if (event.getEventType() == TableEvent.EventType.ERROR) {
                out("    ERROR: %s", event.getMessage());
            }
        });

        AtomicReference<Throwable> error = new AtomicReference<>();
        long t0 = System.currentTimeMillis();
        Thread gameThread = new Thread(() -> {
            try {
                game.start(null);
                game.fireUpdatePlayersEvent();
            } catch (Throwable e) {
                error.set(e);
                Logger.getLogger(BotSpike.class).error("Spiel abgebrochen", e);
            }
        }, ThreadUtils.THREAD_PREFIX_GAME + " " + game.getId());
        gameThread.start();
        gameThread.join(maxMinutes * 60_000L);
        if (gameThread.isAlive()) {
            out("  Zeitlimit erreicht - alle Spieler geben auf");
            for (Player p : game.getPlayers().values()) {
                game.setConcedingPlayer(p.getId());
            }
            gameThread.join(15_000);
            if (gameThread.isAlive()) {
                error.compareAndSet(null, new IllegalStateException("Spiel-Thread haengt"));
            }
        }

        r.durationMs = System.currentTimeMillis() - t0;
        r.turns = game.getTurnNum();
        r.thinkTimeouts = THINK_TIMEOUTS.get() - timeoutsBefore;
        r.avgTurnMs = turnDurations.stream().mapToLong(Long::longValue).average().orElse(0);
        r.maxTurnMs = turnDurations.stream().mapToLong(Long::longValue).max().orElse(0);
        r.error = error.get() == null ? null : error.get().toString();
        StringBuilder w = new StringBuilder();
        for (Player p : game.getPlayers().values()) {
            if (p.hasWon()) {
                w.append("Sieger: ").append(p.getName());
            }
        }
        if (w.length() == 0) {
            w.append("kein Sieger (").append(game.getWinner()).append(")");
        }
        StringBuilder lifes = new StringBuilder();
        for (Player p : game.getPlayers().values()) {
            lifes.append(" ").append(p.getName(), 0, 4).append('=').append(p.getLife()).append(p.hasLost() ? "x" : "")
                    .append("/m").append(match.getMulligan().getMulliganCount(p.getId()));
        }
        r.winner = w + " |" + lifes;
        try {
            game.cleanUp();
            match.cleanUp();
        } catch (Exception ignored) {
            // egal im Spike
        }
        return r;
    }

    private static String playerName(Game game, java.util.UUID id) {
        Player p = id == null ? null : game.getPlayer(id);
        return p == null ? "?" : p.getName();
    }

    private static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "");
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void out(String fmt, Object... args) {
        System.out.println(String.format(Locale.ROOT, fmt, args));
        System.out.flush();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                if (eq > 0) {
                    m.put(a.substring(2, eq), a.substring(eq + 1));
                } else {
                    m.put(a.substring(2), "true");
                }
            }
        }
        return m;
    }

    private static final class GameResult {
        String winner;
        int turns;
        long durationMs;
        double avgTurnMs;
        double maxTurnMs;
        int thinkTimeouts;
        long peakHeapMb;
        String error;
    }

    /**
     * Zaehlt "AI player thinks too long"-Warnungen von ComputerPlayer6.
     */
    private static final class ThinkTimeoutCounter extends AppenderSkeleton {
        @Override
        protected void append(LoggingEvent event) {
            Object msg = event.getMessage();
            if (msg != null && msg.toString().contains("thinks too long")) {
                THINK_TIMEOUTS.incrementAndGet();
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }
}
