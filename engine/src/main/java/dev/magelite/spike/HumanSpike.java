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
 * Args: --games=N --turnCap=T --tempo=BLITZ --seed=S --humans=1..4 --verbose --dumpJson=datei --scenario=swarm|dredge
 * <p>
 * {@code --humans=N}: N automatische Test-Menschen mit eigenem Sitz und eigenem Autopiloten in einem Spiel (Routing-Test).
 * <p>
 * {@code --scenario=swarm}: lange Trigger-Ketten (siehe {@link Scenarios}). Der Test-Spieler spielt dann nur Laender,
 * passt sonst und misst, wie lange jede Kette auf dem Stapel braucht. Beim ersten Angriff greift er per
 * Mehrfach-Angriff ({@code GameHost.combat}) mit allen Kreaturen einen Gegner an und prueft das Ergebnis.
 * <p>
 * {@code --scenario=dredge}: Ersatzeffekt-Wahl beim Ziehen ({@code GameHost.replacement}). 1. Dialog "Keinen
 * anwenden", 2. ersten Effekt per 1-Klick anwenden, 3. "Keinen anwenden" + merken. Prueft, dass danach keine
 * "Dredge ...?"-Frage und nach dem Merken kein Dialog mehr kommt.
 */
public final class HumanSpike {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        int games = Integer.parseInt(opt.getOrDefault("games", "1"));
        int turnCap = Integer.parseInt(opt.getOrDefault("turnCap", "24"));
        int humans = Math.max(1, Math.min(4, Integer.parseInt(opt.getOrDefault("humans", "1"))));
        long seed = Long.parseLong(opt.getOrDefault("seed", String.valueOf(System.nanoTime())));
        boolean verbose = opt.containsKey("verbose");
        TempoSettings.Preset preset = TempoSettings.Preset.valueOf(opt.getOrDefault("tempo", "BLITZ").toUpperCase(Locale.ROOT));
        String scenario = opt.get("scenario");
        if (scenario != null && !Scenarios.exists(scenario)) {
            throw new IllegalArgumentException("Unbekanntes Szenario: " + scenario);
        }

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
            boolean ok = runGame(g, decks, preset, turnCap, humans, new Random(rnd.nextLong()), verbose, opt.get("dumpJson"), scenario);
            if (!ok) {
                failures++;
            }
        }
        out("=== %d Spiele, %d fehlgeschlagen ===", games, failures);
        System.exit(failures > 0 ? 1 : 0);
    }

    /** Nachricht an einen Sitz (Test-Inbox). */
    private record In(GameHost.HumanSeat seat, Object msg) {
    }

    private static boolean runGame(int nr, List<LoadedDeck> decks, TempoSettings.Preset preset, int turnCap, int humans, Random rnd,
                                   boolean verbose, String dumpJson, String scenario) throws Exception {
        List<GameSetup.SeatSpec> specs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (i < humans) {
                specs.add(GameSetup.SeatSpec.human(i + 1, i == 0 ? "Tester" : "Tester " + (i + 1), decks.get(i), null));
            } else {
                specs.add(GameSetup.SeatSpec.bot(decks.get(i)));
            }
        }
        GameSetup setup = new GameSetup(specs, preset);
        GameHost host = GameHost.create(setup);
        host.getTempo().setActionDelayMs(0);
        host.getTempo().setCombatDelayMs(0);
        GameOptions go = GameOptions.getDefault().copy();
        go.rollbackTurnsAllowed = false;
        go.stopOnTurn = turnCap;
        go.stopAtStep = PhaseStep.UPKEEP;
        host.getGame().setGameOptions(go);
        if (scenario != null) {
            Scenarios.apply(scenario, host.getGame(), host.getHumanId());
        }

        out("Spiel %d: Menschen=%s vs Bots=%s", nr, decks.subList(0, humans).stream().map(LoadedDeck::name).toList(),
                decks.subList(humans, 4).stream().map(LoadedDeck::name).toList());

        LinkedBlockingQueue<In> inbox = new LinkedBlockingQueue<>();
        AtomicLong lastMsgAt = new AtomicLong(System.currentTimeMillis());
        AtomicLong bytes = new AtomicLong();
        AtomicLong states = new AtomicLong();
        java.util.concurrent.atomic.AtomicInteger maxState = new java.util.concurrent.atomic.AtomicInteger();
        java.io.PrintWriter dump = dumpJson == null ? null : new java.io.PrintWriter(Files.newBufferedWriter(Path.of(dumpJson)));
        java.util.concurrent.atomic.AtomicInteger recovered = new java.util.concurrent.atomic.AtomicInteger();
        Map<GameHost.HumanSeat, Driver> drivers = new java.util.LinkedHashMap<>();
        for (GameHost.HumanSeat seat : host.seats()) {
            drivers.put(seat, new Driver(host, seat, rnd, verbose, scenario));
            host.attach(seat, msg -> {
                if (msg instanceof Messages.Activity a) {
                    // Herzschlag zaehlt nicht als Fortschritt (sonst greift die STALL-Erkennung nie)
                    recovered.set(a.recovered());
                    return;
                }
                lastMsgAt.set(System.currentTimeMillis());
                if (msg instanceof StateDto) {
                    states.incrementAndGet();
                }
                try {
                    String json = JSON.writeValueAsString(msg);
                    bytes.addAndGet(json.length());
                    if (msg instanceof StateDto) {
                        maxState.accumulateAndGet(json.length(), Math::max);
                    }
                    if (dump != null) {
                        synchronized (dump) {
                            dump.println(json);
                        }
                    }
                } catch (Exception e) {
                    out("JSON-Fehler: %s", e);
                }
                inbox.add(new In(seat, msg));
            });
        }
        GameHost.HumanSeat firstSeat = host.firstSeat();
        Driver driver = drivers.get(firstSeat);
        Map<GameHost.HumanSeat, Messages.GameOver> overs = new java.util.LinkedHashMap<>();
        ChainMeter chains = new ChainMeter();
        host.start();
        long t0 = System.currentTimeMillis();
        boolean stalled = false;
        Messages.GameOver over = null;
        TurnOrderCheck turnOrder = new TurnOrderCheck();
        while (over == null) {
            In in = inbox.poll(500, TimeUnit.MILLISECONDS);
            Object msg = in == null ? null : in.msg();
            Driver d = in == null ? null : drivers.get(in.seat());
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
                d.state = s;
                if (d == driver) {
                    turnOrder.accept(s);
                    chains.accept(s);
                }
            } else if (msg instanceof PromptDto p) {
                d.handle(p);
            } else if (msg instanceof Messages.GameOver g) {
                overs.put(in.seat(), g);
                if (overs.size() == drivers.size()) {
                    over = overs.get(firstSeat);
                }
            } else if (msg instanceof Messages.Toast t && verbose) {
                out("  TOAST %s", t.rich());
            }
        }
        if (dump != null) {
            dump.close();
        }
        long dur = System.currentTimeMillis() - t0;
        out("  Ergebnis: %s | Zuege=%d | %.1f s | Prompts=%s | States=%d | JSON %.1f MB | groesster State %d KB",
                over == null ? "ABGEBROCHEN" : over.result(), over == null ? -1 : over.turns(), dur / 1000.0,
                driver.kinds, states.get(), bytes.get() / 1e6, maxState.get() / 1024);
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
        for (Map.Entry<GameHost.HumanSeat, Driver> e : drivers.entrySet()) {
            if (e.getValue() != driver) {
                Driver o = e.getValue();
                Messages.GameOver og = overs.get(e.getKey());
                out("  %s: Prompts=%s Laender=%d Zauber=%d Passes=%d | gameOver=%s reward=%s", e.getKey().name(), o.kinds, o.lands, o.casts, o.passes,
                        og == null ? "FEHLT" : og.result(), og == null || og.reward() == null ? "-" : "ja");
            }
        }
        boolean allOver = overs.size() == drivers.size();
        if (!allOver) {
            out("  FEHLER: nicht jeder Sitz hat ein gameOver bekommen (%d/%d)", overs.size(), drivers.size());
        }
        boolean promptsEverywhere = drivers.values().stream().allMatch(x -> !x.kinds.isEmpty());
        if (!promptsEverywhere) {
            out("  FEHLER: ein Sitz hat keinen einzigen Prompt bekommen");
        }
        if (driver.swarm) {
            out("  Mehrfach-Angriff: %s", driver.macroResult == null ? "NICHT GETESTET" : driver.macroResult);
        }
        boolean replOk = true;
        if (driver.dredge) {
            boolean shown = driver.state != null && driver.state.replDeclines != null && !driver.state.replDeclines.isEmpty();
            replOk = driver.replErrors == 0 && driver.replDialogs >= 3 && driver.replAcceptedOk && shown;
            out("  Ersatzeffekte: %s | Dialoge=%d Fehler=%d angewendet=%s (%s) gemerkt=%s",
                    replOk ? "OK" : "FEHLER", driver.replDialogs, driver.replErrors, driver.replAccepted,
                    driver.replAcceptedOk ? "auf der Hand" : "NICHT auf der Hand", shown ? driver.state.replDeclines : "FEHLT");
        }
        if (recovered.get() > 0) {
            out("  Verlorene Antworten neu zugestellt (XMage-Race): %d", recovered.get());
        }
        chains.report();
        out("  Sitzordnung (UI): %s", turnOrder.seats);
        out("  Zugfolge: %s", turnOrder.sequence);
        if (turnOrder.errors > 0) {
            out("  FEHLER: Zugfolge weicht %dx von der Sitzordnung ab", turnOrder.errors);
        }
        boolean macroOk = !driver.swarm || (driver.macroResult != null && driver.macroResult.startsWith("OK"));
        return !stalled && over != null && over.error() == null && turnOrder.errors == 0 && macroOk && replOk && allOver && promptsEverywhere;
    }

    /**
     * Misst Trigger-Ketten im State-Strom: vom ersten State mit mindestens {@code MIN} Stapelobjekten bis der
     * Stapel wieder leer ist.
     */
    private static final class ChainMeter {
        static final int MIN = 5;
        final List<String> lines = new ArrayList<>();
        long startedAt;
        int max;
        int turn;

        void accept(StateDto s) {
            int n = s.stack == null ? 0 : s.stack.size();
            long now = System.currentTimeMillis();
            if (startedAt == 0) {
                if (n >= MIN) {
                    startedAt = now;
                    max = n;
                    turn = s.turn;
                }
                return;
            }
            max = Math.max(max, n);
            if (n == 0) {
                long ms = now - startedAt;
                String line = String.format(Locale.ROOT, "Trigger-Kette Zug %d: %d Objekte in %.1f s (%.0f ms/Objekt)",
                        turn, max, ms / 1000.0, ms / (double) max);
                lines.add(line);
                out("  %s", line);
                startedAt = 0;
            }
        }

        void report() {
            if (startedAt != 0) {
                out("  Trigger-Kette Zug %d: NICHT FERTIG nach %.1f s (max %d Objekte)", turn,
                        (System.currentTimeMillis() - startedAt) / 1000.0, max);
            }
            for (String l : lines) {
                out("  %s", l);
            }
        }
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
        final GameHost.HumanSeat seat;
        final Random rnd;
        final boolean verbose;
        /** Szenario-Modus: nur Laender spielen, sonst passen, nicht angreifen */
        final boolean landsOnly;
        final boolean swarm;
        final boolean dredge;
        volatile StateDto state;
        PromptDto lastPrompt;
        final Map<String, Integer> kinds = new HashMap<>();
        final Map<String, Integer> repeats = new HashMap<>();
        final Set<UUID> declaredAttackers = new HashSet<>();
        int attackTurn = -1;
        String stepKey = "";
        int actionsThisStep;
        int lands, casts, attacks, manaClicks, passes;
        /** Mehrfach-Angriff (Szenario): angefragte Angreifer, Ziel, Ergebnis */
        List<UUID> macroIds;
        UUID macroDefender;
        String macroResult;
        /** Ersatzeffekte (Szenario dredge) */
        int replDialogs, replErrors;
        /** bis zum naechsten anderen Prompt darf keine "Dredge ...?"-Frage kommen */
        boolean replNoAsk;
        boolean replAlways;
        String replAccepted;
        boolean replAcceptedOk;

        Driver(GameHost host, GameHost.HumanSeat seat, Random rnd, boolean verbose, String scenario) {
            this.host = host;
            this.seat = seat;
            this.rnd = rnd;
            this.verbose = verbose;
            this.landsOnly = scenario != null;
            this.swarm = "swarm".equalsIgnoreCase(scenario);
            this.dredge = "dredge".equalsIgnoreCase(scenario);
        }

        void handle(PromptDto p) {
            if (dredge && replacement(p)) {
                return;
            }
            if (swarm && "SELECT".equals(p.kind) && "attackers".equals(p.mode) && macroAttack(p)) {
                return;
            }
            lastPrompt = p;
            kinds.merge(p.kind + (p.mode != null ? "/" + p.mode : ""), 1, Integer::sum);
            String repeatKey = p.kind + "|" + p.messageText + "|" + (state == null ? 0 : state.turn);
            int rep = repeats.merge(repeatKey, 1, Integer::sum);
            GameHost.Response r = decide(p, rep);
            if (verbose) {
                out("  [T%s %s] %s '%s' -> %s", state == null ? "?" : state.turn, state == null ? "" : state.step, p.kind,
                        trim(p.messageText, 70), describe(r));
            }
            if (!host.respond(seat, p.id, r)) {
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
                        return landsOnly ? GameHost.Response.ofBool(true) : attackers(p, s);
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

        /**
         * Szenario dredge: Ersatzeffekt-Dialoge ueber {@code GameHost.replacement} beantworten und pruefen, dass die
         * Engine die Folge-Fragen selbst erledigt.
         *
         * @return true, wenn der Prompt beantwortet wurde
         */
        boolean replacement(PromptDto p) {
            StateDto s = state;
            if (replAccepted != null && !replAcceptedOk && s != null && s.hand != null) {
                replAcceptedOk = s.hand.stream().anyMatch(c -> replAccepted.equals(c.name));
            }
            boolean dialog = "CHOOSE_CHOICE".equals(p.kind) && p.choice != null && p.choice.groups != null;
            boolean dredgeAsk = "ASK".equals(p.kind) && p.messageText != null && p.messageText.startsWith("Dredge ");
            if (dredgeAsk && (replNoAsk || replAlways)) {
                replErrors++;
                out("!!! Dredge-Frage trotz Entscheidung: %s", p.messageText);
            }
            if (!dialog) {
                if (!dredgeAsk) {
                    replNoAsk = false;
                }
                return false;
            }
            lastPrompt = p;
            replDialogs++;
            kinds.merge("REPLACEMENT", 1, Integer::sum);
            if (replAlways) {
                replErrors++;
                out("!!! Ersatz-Dialog trotz 'merken'");
            }
            String mode = "decline";
            String key = null;
            boolean always = replDialogs >= 3;
            if (replDialogs == 2) {
                PromptDto.ReplSource src = p.choice.groups.stream().filter(PromptDto.ReplGroup::optional)
                        .map(g -> g.sources().get(0)).findFirst().orElse(null);
                if (src != null) {
                    mode = "accept";
                    key = src.key();
                    replAccepted = src.name();
                }
            }
            out("  [T%s] Ersatz-Dialog %d: %s -> %s%s", s == null ? "?" : s.turn, replDialogs,
                    p.choice.groups.stream().map(g -> g.label() + "x" + g.sources().size()).toList(), mode,
                    "accept".equals(mode) ? " " + replAccepted : always ? " + merken" : "");
            if (!host.replacement(seat, mode, key, always)) {
                replErrors++;
                out("!!! GameHost.replacement abgelehnt");
                return false;
            }
            replNoAsk = true;
            if (always) {
                replAlways = true;
            }
            return true;
        }

        /**
         * Szenario: erster Angriffs-Prompt -> alle moeglichen Angreifer per Mehrfach-Angriff auf einen Gegner;
         * zweiter Angriffs-Prompt (nach dem Makro) -> pruefen und bestaetigen.
         */
        boolean macroAttack(PromptDto p) {
            StateDto s = state;
            if (macroIds == null) {
                if (p.possibleAttackers == null || p.possibleAttackers.isEmpty() || s == null) {
                    return false;
                }
                macroDefender = s.players.stream().filter(pl -> !pl.me && !pl.lost).map(pl -> pl.id).findFirst().orElse(null);
                macroIds = new ArrayList<>(p.possibleAttackers);
                lastPrompt = p;
                if (!host.combat(seat, macroIds, macroDefender)) {
                    macroResult = "FEHLER: combat() abgelehnt";
                    host.respond(seat, p.id, GameHost.Response.ofBool(true));
                }
                return true;
            }
            if (macroResult == null) {
                int ok = 0;
                for (var g : s.combat) {
                    if (g.defenderId().equals(macroDefender)) {
                        for (UUID a : g.attackers()) {
                            if (macroIds.contains(a)) {
                                ok++;
                            }
                        }
                    }
                }
                macroResult = (ok == macroIds.size() ? "OK " : "FEHLER ") + ok + "/" + macroIds.size() + " greifen an";
                attacks += ok;
                lastPrompt = p;
                host.respond(seat, p.id, GameHost.Response.ofBool(true));
                return true;
            }
            return false;
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
            if (landsOnly) {
                for (CardDto c : s.hand) {
                    if (actions.contains(c.id) && c.types != null && c.types.contains("LAND")) {
                        lands++;
                        return GameHost.Response.ofUuid(c.id);
                    }
                }
                passes++;
                return GameHost.Response.ofBool(false);
            }
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
