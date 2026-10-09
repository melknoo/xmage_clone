package dev.magelite.spike;

import dev.magelite.boot.ForgeBoot;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.GameHost;
import dev.magelite.game.TempoSettings;
import dev.magelite.view.dto.Messages;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Headless-Spike: 4 Forge-Bots spielen Commander FFA mit den Sample-Decks (ueber {@link GameHost}, ohne Menschen).
 * <p>
 * Args: --games=N --turnCap=T --tempo=BLITZ|NORMAL|BEDACHT|MAX --seed=S --maxMinutes=M --validate (nur Decks pruefen)
 * --parallel=P (P Spiele gleichzeitig in dieser JVM) --deckDir=D (statt der Sample-Decks)
 * --decks=a;b (diese Decks per Namensteil zuerst; "_" statt Leerzeichen) --slowTurnSec=S (langsame Zuege melden, Standard 10)
 */
public final class BotSpike {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        int games = Integer.parseInt(opt.getOrDefault("games", "3"));
        int turnCap = Integer.parseInt(opt.getOrDefault("turnCap", "40"));
        int maxMinutes = Integer.parseInt(opt.getOrDefault("maxMinutes", "20"));
        TempoSettings.Preset preset = TempoSettings.Preset.valueOf(opt.getOrDefault("tempo", "BLITZ").toUpperCase(Locale.ROOT));
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.nanoTime())));
        int parallel = Math.max(1, Integer.parseInt(opt.getOrDefault("parallel", "1")));

        Path vendor = Path.of(System.getProperty("magelite.vendor", "../../vendor/xmage")).toAbsolutePath().normalize();
        Path forge = Path.of(System.getProperty("magelite.forge", "../../vendor/forge")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, opt.containsKey("verbose"));
        ForgeBoot.Info boot = ForgeBoot.init(forge, Path.of("").toAbsolutePath());
        out("Boot: Forge %s, %d Karten, %d ms, Heap %d MB", boot.forgeVersion(), boot.cards(), boot.ms(), boot.heapMb());

        long tDecks = System.currentTimeMillis();
        List<LoadedDeck> valid = new ArrayList<>();
        int invalid = 0;
        Path deckDir = opt.containsKey("deckDir") ? Path.of(opt.get("deckDir")) : vendor.resolve("sample-decks");
        for (Path f : PocDecks.files(deckDir)) {
            LoadedDeck d = PocDecks.load(f);
            if (d.valid()) {
                valid.add(d);
            } else {
                invalid++;
            }
            if (!d.valid() || opt.containsKey("validate")) {
                out("  %s %-45s main=%d cmd=%s %s %s", d.valid() ? "OK " : "ERR", trim(d.name(), 45), d.mainCount(), d.commanders(),
                        trim(d.validationErrors().replace('\n', ' '), 140), trim(d.importErrors(), 140));
            }
        }
        out("Decks: %d gueltig, %d ungueltig (%d ms)", valid.size(), invalid, System.currentTimeMillis() - tDecks);
        if (opt.containsKey("validate") || valid.size() < 4) {
            System.exit(valid.size() < 4 ? 1 : 0);
        }

        Random rnd = new Random(seed);
        out("Seed=%d, Tempo=%s, Zuglimit=%d", seed, preset, turnCap);
        int failures = 0;
        long totalMs = 0;
        int totalTurns = 0;
        for (int g = 1; g <= games; g += parallel) {
            List<GameHost> hosts = new ArrayList<>();
            for (int k = 0; k < parallel && g + k <= games; k++) {
                Collections.shuffle(valid, rnd);
                List<LoadedDeck> pick = new ArrayList<>();
                for (String want : opt.getOrDefault("decks", "").replace('_', ' ').split(";")) {
                    valid.stream().filter(d -> !want.isBlank() && d.name().contains(want.trim()) && !pick.contains(d))
                            .findFirst().ifPresent(pick::add);
                }
                for (LoadedDeck d : valid) {
                    if (pick.size() < 4 && !pick.contains(d)) {
                        pick.add(d);
                    }
                }
                out("Spiel %d: %s", g + k, pick.stream().map(LoadedDeck::name).toList());
                GameHost host = GameHost.createBots(pick, preset);
                host.setTurnCap(turnCap);
                hosts.add(host);
            }
            long t0 = System.currentTimeMillis();
            hosts.forEach(GameHost::start);
            int slowSec = Integer.parseInt(opt.getOrDefault("slowTurnSec", "10"));
            Thread monitor = slowTurnMonitor(hosts, slowSec);
            for (int k = 0; k < hosts.size(); k++) {
                GameHost host = hosts.get(k);
                long left = Math.max(1, maxMinutes * 60_000L - (System.currentTimeMillis() - t0));
                boolean ended = host.awaitEnd(left);
                long ms = System.currentTimeMillis() - t0;
                int nr = g + k;
                if (!ended) {
                    out("!!! Spiel %d haengt nach %d min - Abbruch", nr, maxMinutes);
                    host.shutdownNow();
                    failures++;
                    continue;
                }
                Messages.GameOver over = host.getGameOver();
                int turns = over == null ? 0 : over.turns();
                totalMs += over == null ? ms : over.durationMs();
                totalTurns += turns;
                System.gc();
                long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
                long dur = over == null ? ms : over.durationMs();
                out("Spiel %d: %s | Zuege=%d | %.1f s (%.2f s/Zug) | Heap %d MB%s", nr, over == null ? "?" : over.result(), turns,
                        dur / 1000.0, turns == 0 ? 0 : dur / 1000.0 / turns, heap,
                        over != null && over.error() != null ? " | FEHLER " + over.error() : "");
                if (over != null) {
                    for (Messages.Placement p : over.placements()) {
                        out("    %d. %-30s Leben=%d%s", p.place(), trim(p.name(), 30), p.life(),
                                p.eliminatedTurn() == null ? "" : " (raus Zug " + p.eliminatedTurn() + ")");
                    }
                }
                if (over == null || over.error() != null) {
                    failures++;
                }
            }
            monitor.interrupt();
        }
        out("=== %d Spiele, %d fehlgeschlagen, Schnitt %.2f s/Zug ===", games, failures,
                totalTurns == 0 ? 0 : totalMs / 1000.0 / totalTurns);
        System.exit(failures > 0 ? 1 : 0);
    }

    /** Meldet Zuege, die laenger als {@code slowSec} dauern (Zugnummer aus dem State, ohne Spiel-Thread). */
    private static Thread slowTurnMonitor(List<GameHost> hosts, int slowSec) {
        Thread t = new Thread(() -> {
            int[] lastTurn = new int[hosts.size()];
            long[] since = new long[hosts.size()];
            java.util.Arrays.fill(since, System.currentTimeMillis());
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < hosts.size(); i++) {
                        int turn = hosts.get(i).currentTurn();
                        if (turn != lastTurn[i]) {
                            long ms = now - since[i];
                            if (lastTurn[i] > 0 && ms > slowSec * 1000L) {
                                out("  langsamer Zug %d: %.0f s", lastTurn[i], ms / 1000.0);
                            }
                            lastTurn[i] = turn;
                            since[i] = now;
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                // Ende
            }
        }, "slow-turns");
        t.setDaemon(true);
        t.start();
        return t;
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
}
