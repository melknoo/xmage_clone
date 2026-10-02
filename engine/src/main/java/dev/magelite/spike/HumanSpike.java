package dev.magelite.spike;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.magelite.boot.CardDbManager;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.GameHost;
import dev.magelite.game.GameSetup;
import dev.magelite.game.TempoSettings;
import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.Messages;
import dev.magelite.view.dto.PermanentDto;
import dev.magelite.view.dto.PlayerDto;
import dev.magelite.view.dto.PromptDto;
import dev.magelite.view.dto.StateDto;
import mage.constants.PhaseStep;
import mage.game.GameOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P0b: Ein automatischer "Test-Mensch" spielt ueber die echte GameHost-API (Prompts/Antworten)
 * gegen 3 Bots. Prueft, dass alle Prompt-Arten beantwortbar sind und nichts haengt.
 * <p>
 * Args: --games=N --turnCap=T --tempo=BLITZ --seed=S --verbose --dumpJson=datei
 */
public final class HumanSpike {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        int games = Integer.parseInt(opt.getOrDefault("games", "1"));
        int turnCap = Integer.parseInt(opt.getOrDefault("turnCap", "24"));
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.nanoTime())));
        boolean verbose = opt.containsKey("verbose");
        TempoSettings.Preset preset = TempoSettings.Preset.valueOf(opt.getOrDefault("tempo", "BLITZ").toUpperCase(Locale.ROOT));

        Path vendor = Path.of(System.getProperty("magelite.vendor", "../../vendor/xmage")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, false);
        CardDbManager.ensure(vendor.resolve("db/cards.h2.mv.db"));

        List<Path> files = new ArrayList<>(DeckLoader.listDeckFiles(vendor.resolve("sample-decks")));
        Random rnd = new Random(seed);
        out("Seed=%d", seed);
        int failures = 0;
        for (int g = 1; g <= games; g++) {
            Collections.shuffle(files, rnd);
            List<LoadedDeck> decks = new ArrayList<>();
            for (Path f : files) {
                LoadedDeck d = DeckLoader.loadFile(f);
                if (d.valid()) {
                    decks.add(d);
                }
                if (decks.size() == 4) {
                    break;
                }
            }
            boolean ok = runGame(g, decks, preset, turnCap, new Random(rnd.nextLong()), verbose, opt.get("dumpJson"));
            if (!ok) {
                failures++;
            }
        }
        out("=== %d Spiele, %d fehlgeschlagen ===", games, failures);
        System.exit(failures > 0 ? 1 : 0);
    }

    private static boolean runGame(int nr, List<LoadedDeck> decks, TempoSettings.Preset preset, int turnCap, Random rnd,
                                   boolean verbose, String dumpJson) throws Exception {
        GameSetup setup = new GameSetup("Tester", decks.get(0), decks.subList(1, 4), preset, null);
        GameHost host = GameHost.create(setup);
        host.getTempo().setActionDelayMs(0);
        host.getTempo().setCombatDelayMs(0);
        GameOptions go = GameOptions.getDefault().copy();
        go.rollbackTurnsAllowed = false;
        go.stopOnTurn = turnCap;
        go.stopAtStep = PhaseStep.UPKEEP;
        host.getGame().setGameOptions(go);

        out("Spiel %d: Ich=%s vs %s", nr, decks.get(0).name(), decks.subList(1, 4).stream().map(LoadedDeck::name).toList());

        LinkedBlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        AtomicLong lastMsgAt = new AtomicLong(System.currentTimeMillis());
        AtomicLong bytes = new AtomicLong();
        AtomicLong states = new AtomicLong();
        java.io.PrintWriter dump = dumpJson == null ? null : new java.io.PrintWriter(Files.newBufferedWriter(Path.of(dumpJson)));
        host.attach(msg -> {
            lastMsgAt.set(System.currentTimeMillis());
            if (msg instanceof StateDto) {
                states.incrementAndGet();
            }
            try {
                String json = JSON.writeValueAsString(msg);
                bytes.addAndGet(json.length());
                if (dump != null) {
                    synchronized (dump) {
                        dump.println(json);
                    }
                }
            } catch (Exception e) {
                out("JSON-Fehler: %s", e);
            }
            inbox.add(msg);
        });

        Driver driver = new Driver(host, rnd, verbose);
        host.start();
        long t0 = System.currentTimeMillis();
        boolean stalled = false;
        Messages.GameOver over = null;
        TurnOrderCheck turnOrder = new TurnOrderCheck();
        while (over == null) {
            Object msg = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (msg == null) {
                if (System.currentTimeMillis() - lastMsgAt.get() > 90_000) {
                    out("!!! STALL: 90 s keine Nachricht. Offener Prompt: %s", driver.lastPrompt == null ? "-" : driver.lastPrompt.kind + " " + driver.lastPrompt.messageText);
                    dumpThreads();
                    stalled = true;
                    host.abort();
                    host.awaitEnd(20_000);
                    break;
                }
                continue;
            }
            if (msg instanceof StateDto s) {
                driver.state = s;
                turnOrder.accept(s);
            } else if (msg instanceof PromptDto p) {
                driver.handle(p);
            } else if (msg instanceof Messages.GameOver g) {
                over = g;
            } else if (msg instanceof Messages.Toast t && verbose) {
                out("  TOAST %s", t.rich());
            }
        }
        if (dump != null) {
            dump.close();
        }
        long dur = System.currentTimeMillis() - t0;
        out("  Ergebnis: %s | Zuege=%d | %.1f s | Prompts=%s | States=%d | JSON %.1f MB",
                over == null ? "ABGEBROCHEN" : over.result(), over == null ? -1 : over.turns(), dur / 1000.0,
                driver.kinds, states.get(), bytes.get() / 1e6);
        if (over != null) {
            for (Messages.Placement p : over.placements()) {
                out("    %d. %-28s Leben=%d %s mull=%d", p.place(), p.name(), p.life(),
                        p.eliminatedTurn() == null ? "" : "(raus Zug " + p.eliminatedTurn() + ")", p.mulligans());
            }
            if (over.error() != null) {
                out("  FEHLER: %s", over.error());
            }
        }
        out("  Aktionen: Laender=%d Zauber/Faehigkeiten=%d Angriffe=%d Mana-Klicks=%d Passes=%d",
                driver.lands, driver.casts, driver.attacks, driver.manaClicks, driver.passes);
        out("  Sitzordnung (UI): %s", turnOrder.seats);
        out("  Zugfolge: %s", turnOrder.sequence);
        if (turnOrder.errors > 0) {
            out("  FEHLER: Zugfolge weicht %dx von der Sitzordnung ab", turnOrder.errors);
        }
        return !stalled && over != null && over.error() == null && turnOrder.errors == 0;
    }

    /**
     * Prueft, ob die Zuege in der Reihenfolge von {@code state.players} (= Sitzordnung der UI) kommen.
     * Zusatzzuege (gleicher Spieler nochmal) gelten nicht als Fehler.
     */
    private static final class TurnOrderCheck {
        final List<String> sequence = new ArrayList<>();
        List<String> seats = List.of();
        int errors;
        private int lastTurn;
        private UUID lastActive;

        void accept(StateDto s) {
            if (s.activePlayerId == null || s.turn == lastTurn || s.players == null) {
                return;
            }
            seats = s.players.stream().map(p -> p.name).toList();
            if (lastActive != null && !s.activePlayerId.equals(lastActive)) {
                UUID expected = nextAlive(s.players, lastActive);
                if (expected != null && !expected.equals(s.activePlayerId)) {
                    errors++;
                    out("!!! Zugfolge: Zug %d erwartet %s, aktiv ist %s", s.turn, name(s.players, expected), name(s.players, s.activePlayerId));
                }
            }
            if (sequence.size() < 16) {
                sequence.add(s.turn + ":" + name(s.players, s.activePlayerId));
            }
            lastTurn = s.turn;
            lastActive = s.activePlayerId;
        }

        private static UUID nextAlive(List<PlayerDto> players, UUID from) {
            int idx = -1;
            for (int i = 0; i < players.size(); i++) {
                if (players.get(i).id.equals(from)) {
                    idx = i;
                }
            }
            if (idx < 0) {
                return null;
            }
            for (int k = 1; k <= players.size(); k++) {
                PlayerDto p = players.get((idx + k) % players.size());
                if (!p.lost) {
                    return p.id;
                }
            }
            return null;
        }

        private static String name(List<PlayerDto> players, UUID id) {
            return players.stream().filter(p -> p.id.equals(id)).map(p -> p.name).findFirst().orElse(String.valueOf(id));
        }
    }

    /**
     * Einfache Zufalls-Strategie, die alle Prompt-Arten bedient.
     */
    private static final class Driver {
        final GameHost host;
        final Random rnd;
        final boolean verbose;
        volatile StateDto state;
        PromptDto lastPrompt;
        final Map<String, Integer> kinds = new HashMap<>();
        final Map<String, Integer> repeats = new HashMap<>();
        final Set<UUID> declaredAttackers = new HashSet<>();
        int attackTurn = -1;
        String stepKey = "";
        int actionsThisStep;
        int lands, casts, attacks, manaClicks, passes;

        Driver(GameHost host, Random rnd, boolean verbose) {
            this.host = host;
            this.rnd = rnd;
            this.verbose = verbose;
        }

        void handle(PromptDto p) {
            lastPrompt = p;
            kinds.merge(p.kind + (p.mode != null ? "/" + p.mode : ""), 1, Integer::sum);
            String repeatKey = p.kind + "|" + p.messageText + "|" + (state == null ? 0 : state.turn);
            int rep = repeats.merge(repeatKey, 1, Integer::sum);
            GameHost.Response r = decide(p, rep);
            if (verbose) {
                out("  [T%s %s] %s '%s' -> %s", state == null ? "?" : state.turn, state == null ? "" : state.step, p.kind,
                        trim(p.messageText, 70), describe(r));
            }
            if (!host.respond(p.id, r)) {
                out("  !! Antwort abgelehnt fuer Prompt %d", p.id);
            }
        }

        GameHost.Response decide(PromptDto p, int rep) {
            StateDto s = state;
            switch (p.kind) {
                case "ASK":
                    if (p.mulligan) {
                        return GameHost.Response.ofBool(false);
                    }
                    return GameHost.Response.ofBool(rep < 3 && rnd.nextInt(4) != 0);
                case "SELECT":
                    if ("attackers".equals(p.mode)) {
                        return attackers(p, s);
                    }
                    if ("blockers".equals(p.mode)) {
                        return GameHost.Response.ofBool(true);
                    }
                    return priority(s);
                case "PICK_TARGET": {
                    List<UUID> chosen = p.chosen == null ? List.of() : p.chosen;
                    List<UUID> open = new ArrayList<>(p.targets == null ? List.of() : p.targets);
                    open.removeAll(chosen);
                    if (p.defenderPick && !open.isEmpty()) {
                        return GameHost.Response.ofUuid(open.get(rnd.nextInt(open.size())));
                    }
                    if (open.isEmpty() || rep > 12 || (!p.required && !chosen.isEmpty())) {
                        return GameHost.Response.ofBool(false);
                    }
                    return GameHost.Response.ofUuid(open.get(rnd.nextInt(open.size())));
                }
                case "PICK_ABILITY":
                case "CHOOSE_ABILITY":
                case "CHOOSE_MODE": {
                    if (p.choices == null || p.choices.isEmpty()) {
                        return GameHost.Response.ofBool(false);
                    }
                    int idx = rep > 2 ? p.choices.size() - 1 : 0;
                    return GameHost.Response.ofUuid(UUID.fromString(p.choices.get(idx).id()));
                }
                case "CHOOSE_CHOICE": {
                    if (p.choice == null || p.choice.items == null || p.choice.items.isEmpty()) {
                        return GameHost.Response.ofString("");
                    }
                    return GameHost.Response.ofString(p.choice.items.get(rnd.nextInt(p.choice.items.size())).key());
                }
                case "PLAY_MANA":
                case "PLAY_X_MANA":
                    return payMana(s, rep);
                case "AMOUNT":
                    return GameHost.Response.ofInt(p.min);
                case "MULTI_AMOUNT": {
                    StringBuilder sb = new StringBuilder();
                    for (PromptDto.AmountItem it : p.items) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(it.value());
                    }
                    return GameHost.Response.ofString(sb.toString());
                }
                case "CHOOSE_PILE":
                    return GameHost.Response.ofBool(true);
                default:
                    return GameHost.Response.ofBool(false);
            }
        }

        GameHost.Response priority(StateDto s) {
            if (s == null || s.actions == null || s.actions.isEmpty()) {
                passes++;
                return GameHost.Response.ofBool(false);
            }
            String key = s.turn + "/" + s.step;
            if (!key.equals(stepKey)) {
                stepKey = key;
                actionsThisStep = 0;
            }
            if (actionsThisStep >= 4) {
                passes++;
                return GameHost.Response.ofBool(false);
            }
            actionsThisStep++;
            Set<UUID> actions = new HashSet<>(s.actions);
            // zuerst Land aus der Hand, dann Zauber aus der Hand, dann irgendwas
            for (CardDto c : s.hand) {
                if (actions.contains(c.id) && c.types != null && c.types.contains("LAND")) {
                    lands++;
                    return GameHost.Response.ofUuid(c.id);
                }
            }
            List<UUID> handActions = new ArrayList<>();
            for (CardDto c : s.hand) {
                if (actions.contains(c.id)) {
                    handActions.add(c.id);
                }
            }
            casts++;
            if (!handActions.isEmpty()) {
                return GameHost.Response.ofUuid(handActions.get(rnd.nextInt(handActions.size())));
            }
            // Commander aus der Command-Zone?
            PlayerDto me = me(s);
            if (me != null && me.command != null) {
                for (var c : me.command) {
                    if (actions.contains(c.id)) {
                        return GameHost.Response.ofUuid(c.id);
                    }
                }
            }
            if (rnd.nextInt(3) == 0) {
                List<UUID> list = new ArrayList<>(actions);
                return GameHost.Response.ofUuid(list.get(rnd.nextInt(list.size())));
            }
            casts--;
            passes++;
            return GameHost.Response.ofBool(false);
        }

        GameHost.Response attackers(PromptDto p, StateDto s) {
            if (s != null && s.turn != attackTurn) {
                attackTurn = s.turn;
                declaredAttackers.clear();
            }
            if (p.possibleAttackers != null) {
                for (UUID a : p.possibleAttackers) {
                    if (!declaredAttackers.contains(a) && rnd.nextInt(3) != 0) {
                        declaredAttackers.add(a);
                        attacks++;
                        return GameHost.Response.ofUuid(a);
                    }
                    declaredAttackers.add(a);
                }
            }
            return GameHost.Response.ofBool(true);
        }

        GameHost.Response payMana(StateDto s, int rep) {
            if (s == null || s.playable == null || rep > 15) {
                return GameHost.Response.ofBool(false);
            }
            PlayerDto me = me(s);
            if (me != null) {
                for (PermanentDto perm : me.battlefield) {
                    if (!perm.tapped && s.playable.containsKey(perm.id)) {
                        manaClicks++;
                        return GameHost.Response.ofUuid(perm.id);
                    }
                }
            }
            return GameHost.Response.ofBool(false);
        }

        PlayerDto me(StateDto s) {
            for (PlayerDto p : s.players) {
                if (p.me) {
                    return p;
                }
            }
            return null;
        }
    }

    private static String describe(GameHost.Response r) {
        if (r.uuid() != null) return "uuid " + r.uuid().toString().substring(0, 8);
        if (r.bool() != null) return "bool " + r.bool();
        if (r.integer() != null) return "int " + r.integer();
        if (r.string() != null) return "str '" + r.string() + "'";
        return "mana";
    }

    private static void dumpThreads() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            String n = e.getKey().getName();
            if (n.startsWith("GAME") || n.startsWith("CALL") || n.startsWith("AI")) {
                out("Thread %s (%s)", n, e.getKey().getState());
                StackTraceElement[] st = e.getValue();
                for (int i = 0; i < Math.min(st.length, 25); i++) {
                    out("    at %s", st[i]);
                }
            }
        }
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
