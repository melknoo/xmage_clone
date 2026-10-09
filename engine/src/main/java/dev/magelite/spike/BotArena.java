package dev.magelite.spike;

import dev.magelite.boot.ForgeBoot;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.GameHost;
import dev.magelite.game.TempoSettings;
import dev.magelite.view.dto.Messages;
import forge.ai.AiProfileUtil;
import forge.util.MyRandom;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bot-Arena: zwei Forge-KI-Profile (Seite A und B) spielen Commander FFA gegeneinander, je 2 Sitze.
 * <p>
 * Fairness: Sitze A B A B; jede Deck-Auswahl laeuft zweimal mit getauschten Seiten (gleiches Deck, gleicher Sitz,
 * anderes Profil; beide Spiele eines Paars bekommen denselben Zufalls-Seed fuer Mischen und Startspieler). Gemessen:
 * Siege und Platzierungspunkte (1. = 3, 2. = 2, 3. = 1, 4. = 0; bei Zuglimit werden die Ueberlebenden nach Leben
 * platziert). Gleich stark = 50 % der Siege bzw. 1,5 Punkte pro Sitz.
 * <p>
 * Args: --a=MageLite --b=Reckless --games=N (gerade) --tempo=BLITZ|NORMAL|BEDACHT|MAX --turnCap=T --seed=S
 * --maxMinutes=M (je Spiel) --verbose
 * <p>
 * Profile: die Forge-Profile aus {@code vendor/forge/res/ai} (Default, Cautious, Reckless, Experimental) und
 * {@link ForgeBoot#AI_PROFILE} ("MageLite" = Default ohne zufaellige Blocker-Trades, das Profil der App). Standard
 * {@code MageLite} gegen {@code Reckless}: Reckless ist das Profil mit dem groessten Unterschied im Spielstil
 * (Angriffslust, Risiko), MageLite gegen Default waere nahezu identisch. Aussagekraeftig ist der Vergleich ab etwa
 * 30 Spielen; Forge-Timeouts der KI werden je Spiel mitgezaehlt (nicht nach Seite aufgeschluesselt).
 * <p>
 * CSV pro Spiel: {@code run/arena/arena-<zeit>.csv}.
 */
public final class BotArena {

    private static final AtomicInteger THINK_TIMEOUTS = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        int games = Integer.parseInt(opt.getOrDefault("games", "6"));
        int turnCap = Integer.parseInt(opt.getOrDefault("turnCap", "40"));
        int maxMinutes = Integer.parseInt(opt.getOrDefault("maxMinutes", "20"));
        TempoSettings.Preset preset = TempoSettings.Preset.valueOf(opt.getOrDefault("tempo", "BLITZ").toUpperCase(Locale.ROOT));
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.nanoTime())));
        boolean verbose = opt.containsKey("verbose");
        String a = opt.getOrDefault("a", ForgeBoot.AI_PROFILE);
        String b = opt.getOrDefault("b", "Reckless");

        Path forge = Path.of(System.getProperty("magelite.forge", "../../vendor/forge")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, verbose);
        ForgeBoot.Info boot = ForgeBoot.init(forge, Path.of("").toAbsolutePath());
        out("Boot: Forge %s, %d Karten, %d ms, Heap %d MB", boot.forgeVersion(), boot.cards(), boot.ms(), boot.heapMb());
        System.setOut(new TimeoutFilter(System.out, verbose));
        System.setErr(new TimeoutFilter(System.err, verbose));

        List<String> known = new ArrayList<>(AiProfileUtil.getAvailableProfiles());
        known.add(ForgeBoot.AI_PROFILE);
        for (String p : List.of(a, b)) {
            if (!known.contains(p)) {
                out("Unbekanntes KI-Profil: %s (bekannt: %s)", p, known);
                System.exit(1);
            }
        }

        List<LoadedDeck> valid = new ArrayList<>();
        for (PocDecks.Ref f : PocDecks.list(null)) {
            try {
                LoadedDeck d = PocDecks.load(f);
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
        writeCsv(csv, "game;seed;a;b;tempo;seatSides;decks;winnerSide;pointsA;pointsB;turns;durationS;avgTurnMs;maxTurnMs;thinkTimeouts;eliminated;error");

        out("Arena: A=%s B=%s tempo=%s spiele=%d turnCap=%d seed=%d decks=%d", a, b, preset, games, turnCap, seed, valid.size());
        out("CSV: %s", csv);

        Random rnd = new Random(seed);
        int winsA = 0, winsB = 0, draws = 0, errors = 0;
        double pointsA = 0, pointsB = 0;
        long totalMs = 0;
        int totalTimeouts = 0;
        List<LoadedDeck> decks = null;
        long pairSeed = 0;
        for (int g = 1; g <= games; g++) {
            boolean mirror = g % 2 == 0;
            if (!mirror) {
                List<LoadedDeck> pool = new ArrayList<>(valid);
                Collections.shuffle(pool, rnd);
                decks = new ArrayList<>(pool.subList(0, 4));
                pairSeed = rnd.nextLong();
            }
            // Sitz i: A auf geraden Sitzen, im Spiegel-Spiel umgekehrt
            boolean[] sideA = new boolean[4];
            String[] profiles = new String[4];
            for (int i = 0; i < 4; i++) {
                sideA[i] = (i % 2 == 0) != mirror;
                profiles[i] = sideA[i] ? a : b;
            }
            Result r = runGame(decks, profiles, preset, turnCap, maxMinutes, pairSeed, verbose);
            totalMs += r.durationMs;
            totalTimeouts += r.thinkTimeouts;
            String winnerSide = r.winnerSeat < 0 ? "-" : (sideA[r.winnerSeat] ? "A" : "B");
            if (r.error != null) {
                errors++;
            }
            if (r.winnerSeat < 0) {
                draws++;
            } else if (sideA[r.winnerSeat]) {
                winsA++;
            } else {
                winsB++;
            }
            double pa = 0, pb = 0;
            for (int i = 0; i < 4; i++) {
                if (sideA[i]) {
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
                seatSides.append(sideA[i] ? 'A' : 'B');
                deckNames.append(i > 0 ? "|" : "").append(decks.get(i).name().replace(';', ','));
            }
            writeCsv(csv, String.join(";", String.valueOf(g), String.valueOf(seed), a, b, preset.name(),
                    seatSides.toString(), deckNames.toString(), winnerSide, fmt(pa), fmt(pb), String.valueOf(r.turns),
                    fmt(r.durationMs / 1000.0), fmt(r.avgTurnMs), fmt(r.maxTurnMs), String.valueOf(r.thinkTimeouts),
                    r.eliminated.toString(), r.error == null ? "" : r.error.replace(';', ',').replace('\n', ' ')));
        }

        out("=== Ergebnis A=%s vs B=%s (%s, %d Spiele, %.0f min) ===", a, b, preset, games, totalMs / 60000.0);
        out("Siege: A %d (%.0f %%) | B %d (%.0f %%) | Remis %d | Fehler %d",
                winsA, pct(winsA, winsA + winsB), winsB, pct(winsB, winsA + winsB), draws, errors);
        out("Punkte pro Sitz: A %.2f | B %.2f (gleich stark = 1,50)", pointsA / (2.0 * games), pointsB / (2.0 * games));
        out("Think-Timeouts gesamt: %d", totalTimeouts);
        System.exit(errors > 0 ? 1 : 0);
    }

    /** Ein Spiel: Sitz i spielt {@code decks[i]} mit {@code profiles[i]}; {@code gameSeed} fuer Mischen und Startspieler. */
    private static Result runGame(List<LoadedDeck> decks, String[] profiles, TempoSettings.Preset preset, int turnCap,
                                  int maxMinutes, long gameSeed, boolean verbose) throws Exception {
        MyRandom.setRandom(new Random(gameSeed));
        GameHost host = GameHost.createBots(decks, preset, List.of(profiles));
        host.getTempo().setActionDelayMs(0);
        host.getTempo().setCombatDelayMs(0);
        host.setTurnCap(turnCap);
        List<UUID> seatIds = new ArrayList<>(host.getDeckNames().keySet()); // Sitzreihenfolge

        Result r = new Result();
        int timeoutsBefore = THINK_TIMEOUTS.get();
        long t0 = System.currentTimeMillis();
        host.start();
        // Zugdauern: Zugnummer des Hosts (State-Drossel 60 ms) in kurzem Takt lesen
        List<Long> turnDurations = new ArrayList<>();
        int lastTurn = 0;
        long turnStart = t0;
        while (!host.awaitEnd(25)) {
            long now = System.currentTimeMillis();
            int t = host.currentTurn();
            if (t != lastTurn) {
                if (lastTurn > 0) {
                    turnDurations.add(now - turnStart);
                }
                turnStart = now;
                lastTurn = t;
                if (verbose) {
                    out("--- Zug %d", t);
                }
            }
            if (now - t0 > maxMinutes * 60_000L) {
                out("  Zeitlimit erreicht - Spiel wird abgebrochen");
                host.shutdownNow();
                r.error = "Zeitlimit " + maxMinutes + " min";
                break;
            }
        }
        r.thinkTimeouts = THINK_TIMEOUTS.get() - timeoutsBefore;
        r.avgTurnMs = turnDurations.stream().mapToLong(Long::longValue).average().orElse(0);
        r.maxTurnMs = turnDurations.stream().mapToLong(Long::longValue).max().orElse(0);

        Messages.GameOver over = host.getGameOver();
        if (over == null) {
            r.durationMs = System.currentTimeMillis() - t0;
            r.error = r.error == null ? "kein gameOver (Spiel-Thread abgestuerzt?)" : r.error;
            r.points = new double[4];
            return r;
        }
        r.durationMs = over.durationMs();
        r.turns = over.turns();
        if (over.error() != null) {
            r.error = over.error();
        }
        scoreGame(r, over, seatIds);
        return r;
    }

    /**
     * Platzierung: Sieger 3, dann rueckwaerts in Ausscheide-Reihenfolge (erster Ausgeschiedener 0, zweiter 1, ...);
     * Ueberlebende ohne Sieger (Zuglimit) nach Leben, Gleichstand teilt sich die Punkte.
     */
    private static void scoreGame(Result r, Messages.GameOver over, List<UUID> seatIds) {
        int n = seatIds.size();
        r.points = new double[n];
        Messages.Placement[] bySeat = new Messages.Placement[n];
        for (Messages.Placement p : over.placements()) {
            int seat = seatIds.indexOf(p.playerId());
            if (seat >= 0) {
                bySeat[seat] = p;
            }
        }
        List<Integer> eliminated = new ArrayList<>();
        List<Integer> alive = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (bySeat[i] == null) {
                continue;
            }
            if (over.winnerId() != null && over.winnerId().equals(seatIds.get(i))) {
                r.winnerSeat = i;
                r.winnerName = bySeat[i].name();
            } else if (bySeat[i].eliminatedTurn() != null) {
                eliminated.add(i);
            } else {
                alive.add(i);
            }
        }
        // schlechtester Platz = zuerst ausgeschieden
        eliminated.sort(Comparator.comparingInt((Integer i) -> bySeat[i].place()).reversed());
        r.eliminated.addAll(eliminated);
        for (int k = 0; k < eliminated.size(); k++) {
            r.points[eliminated.get(k)] = k;
        }
        if (r.winnerSeat >= 0) {
            r.points[r.winnerSeat] = n - 1;
            for (int i : alive) {
                r.points[i] = n - 2; // sollte nicht vorkommen (Sieger heisst alle anderen raus)
            }
        } else if (!alive.isEmpty()) {
            alive.sort(Comparator.comparingInt((Integer i) -> bySeat[i].life()));
            int k = eliminated.size();
            int pos = 0;
            while (pos < alive.size()) {
                int end = pos;
                int lifeAtPos = bySeat[alive.get(pos)].life();
                while (end + 1 < alive.size() && bySeat[alive.get(end + 1)].life() == lifeAtPos) {
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

    /**
     * Zaehlt die KI-Timeouts von Forge ("AI eval thread at timeout" auf stdout, {@link TimeoutException} auf stderr)
     * und unterdrueckt deren Stack-Ausgabe (ausser mit --verbose).
     */
    private static final class TimeoutFilter extends PrintStream {
        private final boolean passThrough;
        private boolean inTimeoutTrace;

        TimeoutFilter(PrintStream target, boolean passThrough) {
            super(target, true);
            this.passThrough = passThrough;
        }

        @Override
        public void println(String x) {
            if (x != null && x.startsWith("AI eval thread at timeout")) {
                THINK_TIMEOUTS.incrementAndGet();
                if (!passThrough) {
                    return;
                }
            }
            if (inTimeoutTrace && x != null && x.startsWith("\tat ")) {
                if (!passThrough) {
                    return;
                }
            } else {
                inTimeoutTrace = false;
            }
            super.println(x);
        }

        @Override
        public void println(Object x) {
            if (x instanceof TimeoutException) {
                inTimeoutTrace = true;
                if (!passThrough) {
                    return;
                }
            }
            super.println(x);
        }
    }
}
