package dev.magelite.spike;

import dev.magelite.boot.CardDbManager;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.BotTuning;
import dev.magelite.game.MageLiteBot;
import dev.magelite.game.MageLiteMatch;
import dev.magelite.game.TempoSettings;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.events.TableEvent;
import mage.player.ai.ComputerPlayerMCTS;
import mage.players.Player;
import mage.util.ThreadUtils;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bot-Arena: zwei KI-Varianten (Seite A und B) spielen Commander FFA gegeneinander, je 2 Sitze.
 * <p>
 * Fairness: Sitze A B A B; jede Deck-Auswahl laeuft zweimal mit getauschten Seiten (gleiches Deck, gleicher Sitz,
 * andere KI). Gemessen: Siege und Platzierungspunkte (1. = 3, 2. = 2, 3. = 1, 4. = 0; bei Zuglimit werden die
 * Ueberlebenden nach Leben platziert). Gleich stark = 50 % der Siege bzw. 1,5 Punkte pro Sitz.
 * <p>
 * Args: --a=improved|original|mcts --b=... --games=N (gerade) --tempo=BLITZ|NORMAL|BEDACHT|MAX --turnCap=T
 * --seed=S --maxMinutes=M --levers=FFA_EVAL,FFA_ATTACK,REACT_IN_COMBAT (was "improved" nutzt) --w=0.5 --elim=5000
 * --mctsSkill=3 --verbose
 * <p>
 * CSV pro Spiel: {@code run/arena/arena-<zeit>.csv}.
 */
public final class BotArena {

    private static final AtomicInteger THINK_TIMEOUTS = new AtomicInteger();
    private static final AtomicInteger TIMEOUTS_A = new AtomicInteger();
    private static final AtomicInteger TIMEOUTS_B = new AtomicInteger();

    private record Side(String kind) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        int games = Integer.parseInt(opt.getOrDefault("games", "6"));
        int turnCap = Integer.parseInt(opt.getOrDefault("turnCap", "40"));
        int maxMinutes = Integer.parseInt(opt.getOrDefault("maxMinutes", "20"));
        TempoSettings.Preset preset = TempoSettings.Preset.valueOf(opt.getOrDefault("tempo", "BLITZ").toUpperCase(Locale.ROOT));
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.nanoTime())));
        boolean verbose = opt.containsKey("verbose");
        Side a = new Side(opt.getOrDefault("a", "improved"));
        Side b = new Side(opt.getOrDefault("b", "original"));
        Set<BotTuning.Lever> levers = EnumSet.noneOf(BotTuning.Lever.class);
        for (String l : opt.getOrDefault("levers", "FFA_EVAL,FFA_ATTACK,REACT_IN_COMBAT").split(",")) {
            if (!l.isBlank()) {
                levers.add(BotTuning.Lever.valueOf(l.trim().toUpperCase(Locale.ROOT)));
            }
        }
        if (opt.containsKey("w")) {
            BotTuning.ffaMaxWeight = Double.parseDouble(opt.get("w"));
        }
        if (opt.containsKey("elim")) {
            BotTuning.ffaEliminationBonus = Integer.parseInt(opt.get("elim"));
        }
        int mctsSkill = Integer.parseInt(opt.getOrDefault("mctsSkill", "3"));

        Path vendor = Path.of(System.getProperty("magelite.vendor", "../../vendor/xmage")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, verbose);
        Logger.getLogger("mage.player.ai").addAppender(new ThinkTimeoutCounter());
        CardDbManager.ensure(vendor.resolve("db/cards.h2.mv.db"));
        if (!BotTuning.checkFfaEvaluator()) {
            out("WARNUNG: Original-XMage-Bewertung geladen - FFA_EVAL wirkt nicht");
        }

        List<LoadedDeck> valid = new ArrayList<>();
        for (Path f : DeckLoader.listDeckFiles(vendor.resolve("sample-decks"))) {
            try {
                LoadedDeck d = DeckLoader.loadFile(f);
                if (d.valid()) {
                    valid.add(d);
                }
            } catch (Exception ignored) {
                // ungueltige Decks ueberspringen
            }
        }
        if (valid.size() < 4) {
            out("Zu wenige gueltige Decks: %d", valid.size());
            System.exit(1);
        }

        Path csv = Path.of("arena").toAbsolutePath().resolve("arena-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
        Files.createDirectories(csv.getParent());
        writeCsv(csv, "game;seed;a;b;levers;w;elim;tempo;seatSides;decks;winnerSide;pointsA;pointsB;turns;durationS;avgTurnMs;maxTurnMs;thinkTimeouts;eliminated;error");

        out("Arena: A=%s B=%s levers=%s w=%.2f elim=%d tempo=%s spiele=%d turnCap=%d seed=%d decks=%d",
                a.kind, b.kind, levers, BotTuning.ffaMaxWeight, BotTuning.ffaEliminationBonus, preset, games, turnCap, seed, valid.size());
        out("CSV: %s", csv);

        Random rnd = new Random(seed);
        int winsA = 0, winsB = 0, draws = 0, errors = 0;
        double pointsA = 0, pointsB = 0;
        long totalMs = 0;
        List<LoadedDeck> decks = null;
        for (int g = 1; g <= games; g++) {
            boolean mirror = g % 2 == 0;
            if (!mirror) {
                List<LoadedDeck> pool = new ArrayList<>(valid);
                Collections.shuffle(pool, rnd);
                decks = new ArrayList<>(pool.subList(0, 4));
            }
            // Sitz i: A auf geraden Sitzen, im Spiegel-Spiel umgekehrt
            Side[] seats = new Side[4];
            for (int i = 0; i < 4; i++) {
                seats[i] = (i % 2 == 0) != mirror ? a : b;
            }
            Result r = runGame(g, decks, seats, a, levers, preset, mctsSkill, turnCap, maxMinutes, verbose);
            totalMs += r.durationMs;
            String winnerSide = r.winnerSeat < 0 ? "-" : (seats[r.winnerSeat] == a ? "A" : "B");
            if (r.error != null) {
                errors++;
            }
            if (r.winnerSeat < 0) {
                draws++;
            } else if (seats[r.winnerSeat] == a) {
                winsA++;
            } else {
                winsB++;
            }
            double pa = 0, pb = 0;
            for (int i = 0; i < 4; i++) {
                if (seats[i] == a) {
                    pa += r.points[i];
                } else {
                    pb += r.points[i];
                }
            }
            pointsA += pa;
            pointsB += pb;
            out("Spiel %d%s: Sieger %s | Punkte A %.1f B %.1f | Zuege %d | %.0f s | %.0f ms/Zug (max %.0f) | Timeouts %d%s",
                    g, mirror ? " (Spiegel)" : "", winnerSide.equals("-") ? "keiner" : winnerSide + " " + r.winnerName, pa, pb,
                    r.turns, r.durationMs / 1000.0, r.avgTurnMs, r.maxTurnMs, r.thinkTimeouts, r.error != null ? " | FEHLER " + r.error : "");
            out("    Stand: A %d Siege / %.2f Pkt pro Sitz | B %d Siege / %.2f Pkt pro Sitz | Remis %d",
                    winsA, pointsA / (2.0 * g), winsB, pointsB / (2.0 * g), draws);

            StringBuilder seatSides = new StringBuilder();
            StringBuilder deckNames = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                seatSides.append(seats[i] == a ? 'A' : 'B');
                deckNames.append(i > 0 ? "|" : "").append(decks.get(i).name().replace(';', ','));
            }
            writeCsv(csv, String.join(";", String.valueOf(g), String.valueOf(seed), a.kind, b.kind, levers.toString(),
                    String.valueOf(BotTuning.ffaMaxWeight), String.valueOf(BotTuning.ffaEliminationBonus), preset.name(),
                    seatSides.toString(), deckNames.toString(), winnerSide, fmt(pa), fmt(pb), String.valueOf(r.turns),
                    fmt(r.durationMs / 1000.0), fmt(r.avgTurnMs), fmt(r.maxTurnMs), String.valueOf(r.thinkTimeouts),
                    r.eliminated.toString(), r.error == null ? "" : r.error.replace(';', ',')));
        }

        out("=== Ergebnis A=%s vs B=%s (%s, %d Spiele, %.0f min) ===", a.kind, b.kind, preset, games, totalMs / 60000.0);
        out("Siege: A %d (%.0f %%) | B %d (%.0f %%) | Remis %d | Fehler %d",
                winsA, pct(winsA, winsA + winsB), winsB, pct(winsB, winsA + winsB), draws, errors);
        out("Punkte pro Sitz: A %.2f | B %.2f (gleich stark = 1,50)", pointsA / (2.0 * games), pointsB / (2.0 * games));
        out("Think-Timeouts gesamt: %d (A %d, B %d)", THINK_TIMEOUTS.get(), TIMEOUTS_A.get(), TIMEOUTS_B.get());
        System.exit(errors > 0 ? 1 : 0);
    }

    private static Result runGame(int nr, List<LoadedDeck> decks, Side[] seats, Side a, Set<BotTuning.Lever> levers,
                                  TempoSettings.Preset preset, int mctsSkill, int turnCap, int maxMinutes,
                                  boolean verbose) throws Exception {
        TempoSettings tempo = new TempoSettings(preset);
        tempo.setActionDelayMs(0);
        tempo.setCombatDelayMs(0);

        MageLiteMatch match = new MageLiteMatch(MageLiteMatch.defaultOptions("Arena " + nr));
        List<UUID> seatIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Side side = seats[i];
            String name = (side == a ? "A" : "B") + (i + 1) + "-" + side.kind + " [" + trim(decks.get(i).name(), 24) + "]";
            Player p;
            if (side.kind.equals("mcts")) {
                p = new ComputerPlayerMCTS(name, RangeOfInfluence.ALL, mctsSkill);
            } else {
                MageLiteBot bot = new MageLiteBot(name, RangeOfInfluence.ALL, tempo);
                Set<BotTuning.Lever> off = EnumSet.allOf(BotTuning.Lever.class);
                if (side.kind.equals("improved")) {
                    off.removeAll(levers);
                } else if (!side.kind.equals("original")) {
                    throw new IllegalArgumentException("Unbekannte Seite: " + side.kind);
                }
                BotTuning.disable(bot.getId(), off);
                p = bot;
            }
            seatIds.add(p.getId());
            match.addPlayer(p, decks.get(i).newDeck());
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
        Result r = new Result();
        long[] turnStart = {System.currentTimeMillis()};
        int[] lastTurn = {0};
        List<Long> turnDurations = new ArrayList<>();

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
                    if (verbose) {
                        out("--- Zug %d", t);
                    }
                }
                trackEliminations(game, seatIds, r.eliminated);
            } else if (verbose && (event.getEventType() == TableEvent.EventType.INFO || event.getEventType() == TableEvent.EventType.STATUS)) {
                out("    %s", event.getMessage() == null ? "" : event.getMessage().replaceAll("<[^>]+>", ""));
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
                Logger.getLogger(BotArena.class).error("Spiel abgebrochen", e);
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
        trackEliminations(game, seatIds, r.eliminated);

        // Platzierung: Sieger 3, dann rueckwaerts in Ausscheide-Reihenfolge; Ueberlebende ohne Sieger teilen
        r.points = new double[4];
        List<Integer> alive = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Player p = game.getPlayer(seatIds.get(i));
            if (p != null && p.hasWon()) {
                r.winnerSeat = i;
                r.winnerName = p.getName();
            } else if (!r.eliminated.contains(i)) {
                alive.add(i);
            }
        }
        for (int k = 0; k < r.eliminated.size(); k++) {
            r.points[r.eliminated.get(k)] = k; // erster Ausgeschiedener 0, zweiter 1, ...
        }
        if (r.winnerSeat >= 0) {
            r.points[r.winnerSeat] = 3;
            for (int i : alive) {
                r.points[i] = 2; // sollte nicht vorkommen (Sieger heisst alle anderen raus)
            }
        } else if (!alive.isEmpty()) {
            // Zuglimit: Ueberlebende nach Leben platzieren (Gleichstand teilt sich die Plaetze)
            alive.sort(Comparator.comparingInt((Integer i) -> life(game, seatIds.get(i))));
            int k = r.eliminated.size();
            int pos = 0;
            while (pos < alive.size()) {
                int end = pos;
                int lifeAtPos = life(game, seatIds.get(alive.get(pos)));
                while (end + 1 < alive.size() && life(game, seatIds.get(alive.get(end + 1))) == lifeAtPos) {
                    end++;
                }
                double share = 0;
                for (int j = pos; j <= end; j++) {
                    share += k + j;
                }
                share /= (end - pos + 1);
                for (int j = pos; j <= end; j++) {
                    r.points[alive.get(j)] = share;
                }
                pos = end + 1;
            }
        }
        for (UUID id : seatIds) {
            BotTuning.clear(id);
        }
        try {
            game.cleanUp();
            match.cleanUp();
        } catch (Exception ignored) {
            // egal in der Arena
        }
        return r;
    }

    private static int life(Game game, UUID playerId) {
        Player p = game.getPlayer(playerId);
        return p == null ? 0 : p.getLife();
    }

    private static void trackEliminations(Game game, List<UUID> seatIds, List<Integer> eliminated) {
        for (int i = 0; i < seatIds.size(); i++) {
            Player p = game.getPlayer(seatIds.get(i));
            if (p != null && p.hasLost() && !eliminated.contains(i)) {
                eliminated.add(i);
            }
        }
    }

    private static double pct(int part, int total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static void writeCsv(Path csv, String line) throws IOException {
        Files.writeString(csv, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
        for (String arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    m.put(arg.substring(2, eq), arg.substring(eq + 1));
                } else {
                    m.put(arg.substring(2), "true");
                }
            }
        }
        return m;
    }

    private static final class Result {
        int winnerSeat = -1;
        String winnerName;
        int turns;
        long durationMs;
        double avgTurnMs;
        double maxTurnMs;
        int thinkTimeouts;
        String error;
        final List<Integer> eliminated = new ArrayList<>();
        double[] points;
    }

    /** Zaehlt "AI player thinks too long"-Warnungen von ComputerPlayer6. */
    private static final class ThinkTimeoutCounter extends AppenderSkeleton {
        @Override
        protected void append(LoggingEvent event) {
            Object msg = event.getMessage();
            if (msg == null) {
                return;
            }
            String s = msg.toString();
            if (s.contains("thinks too long")) {
                THINK_TIMEOUTS.incrementAndGet();
            } else if (s.startsWith(" - player: ") && s.length() > 11) {
                // Folgezeile der Warnung mit dem Spielernamen ("A1-improved [...]")
                char side = s.charAt(11);
                if (side == 'A') {
                    TIMEOUTS_A.incrementAndGet();
                } else if (side == 'B') {
                    TIMEOUTS_B.incrementAndGet();
                }
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
