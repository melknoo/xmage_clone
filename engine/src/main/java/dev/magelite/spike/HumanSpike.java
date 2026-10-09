package dev.magelite.spike;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.magelite.boot.ForgeBoot;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.GameHost;
import dev.magelite.game.GameSetup;
import dev.magelite.game.TempoSettings;
import dev.magelite.stats.StatsSink;
import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.Messages;
import dev.magelite.view.dto.PermanentDto;
import dev.magelite.view.dto.PlayerDto;
import dev.magelite.view.dto.PromptDto;
import dev.magelite.view.dto.StateDto;

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
 * Args: --games=N --turnCap=T --tempo=BLITZ --seed=S --humans=1..4 --verbose --dumpJson=datei --scenario=swarm|dredge|gemstone|necro|convoke
 * --spectate --decks=a;b (diese Decks per Dateinamensteil zuerst, erstes = Test-Mensch; "_" statt Leerzeichen)
 * <p>
 * {@code --spectate}: Spiel wie ein Tisch-Spiel zuschaubar machen und einen In-Process-Zuschauer anmelden, der jede
 * Nachricht auf Lecks prueft ({@link SpectatorCheck}); ein Sitz schreibt im Spiel eine Chat-Zeile.
 * <p>
 * {@code --humans=N}: N automatische Test-Menschen mit eigenem Sitz und eigenem Autopiloten in einem Spiel (Routing-Test).
 * <p>
 * {@code --scenario=swarm}: lange Trigger-Ketten (siehe Scenarios). Der Test-Spieler spielt dann nur Laender,
 * passt sonst und misst, wie lange jede Kette auf dem Stapel braucht. Beim ersten Angriff greift er per
 * Mehrfach-Angriff ({@code GameHost.combat}) mit allen Kreaturen einen Gegner an und prueft das Ergebnis; danach
 * nimmt er den Angriff per {@code GameHost.combatReset} komplett zurueck und prueft, dass niemand mehr angreift.
 * <p>
 * {@code --scenario=necro}: Necropotence im Spiel, ich beginne. Im ersten Hauptsegment "Pay 1 life" ueber
 * {@code GameHost.repeat} 5-mal aktivieren; prueft Leben -5 und Exil +5, ohne dass ein fremder Prompt kommt.
 * <p>
 * {@code --scenario=gemstone}: Starthand-Aktion (Gemstone Caverns, Bot beginnt). Prueft, dass die Ja/Nein-Frage vor
 * dem ersten Zug kommt und die Karte nach "Ja" + Exil-Wahl auf dem Spielfeld liegt.
 * <p>
 * {@code --scenario=convoke}: X-Zauber und Einberufen. Main 1: Knopf-Ziel "combat", F10 bei leerem Stapel abgelehnt,
 * Blaze mit X=2 (Stapel zeigt {@code x}); dann Guardian of Vitu-Ghazi: Mana-Prompt mit Knopf "Einberufen" und 6
 * Kreaturen, kein Auto-Start; "Automatisch bezahlen" tappt nur die 2 Laender, den Rest zahlen Klicks auf Kreaturen
 * ({@code GameHost.specialPay}) ohne weitere Fragen. Danach Main-2-Stopp mit Ziel "end", dort F9 ("bis zu meinem
 * Zug"): im Gegnerzug per F3 abbrechen, erneut F9 und per "Passen manuell" abbrechen; danach muss ein Stopp in der
 * Endphase eines Gegners kommen. Im naechsten eigenen Zug Ziel "combat".
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
        boolean spectate = opt.containsKey("spectate");
        if (scenario != null && !Scenarios.exists(scenario)) {
            throw new IllegalArgumentException("Unbekanntes Szenario: " + scenario);
        }

        Path forge = Path.of(System.getProperty("magelite.forge", "../../vendor/forge")).toAbsolutePath().normalize();
        Path logs = Path.of("logs").toAbsolutePath();
        Files.createDirectories(logs);
        LogConfig.configure(logs, false);
        ForgeBoot.Info boot = ForgeBoot.init(forge, Path.of("").toAbsolutePath());
        out("Forge %s: %d Karten, Boot %d ms", boot.forgeVersion(), boot.cards(), boot.ms());

        List<PocDecks.Ref> files = new ArrayList<>(PocDecks.list(null));
        Random rnd = new Random(seed);
        out("Seed=%d", seed);
        int failures = 0;
        for (int g = 1; g <= games; g++) {
            Collections.shuffle(files, rnd);
            List<LoadedDeck> decks = new ArrayList<>();
            for (String want : opt.getOrDefault("decks", "").replace('_', ' ').split(";")) {
                for (PocDecks.Ref f : files) {
                    if (!want.isBlank() && f.fileName().contains(want.trim())) {
                        LoadedDeck d = PocDecks.load(f);
                        if (d.valid()) {
                            decks.add(d);
                            break;
                        }
                    }
                }
            }
            for (PocDecks.Ref f : files) {
                if (decks.stream().anyMatch(x -> f.fileName().startsWith(x.name()))) {
                    continue;
                }
                LoadedDeck d = PocDecks.load(f);
                if (d.valid()) {
                    decks.add(d);
                }
                if (decks.size() == 4) {
                    break;
                }
            }
            boolean ok = runGame(g, decks, preset, turnCap, humans, new Random(rnd.nextLong()), verbose, opt.get("dumpJson"), scenario, spectate,
                    opt.get("leave"));
            if (!ok) {
                failures++;
            }
        }
        reportLatency();
        out("Heap nach GC je Spiel (MB): %s", HEAPS);
        out("=== %d Spiele, %d fehlgeschlagen ===", games, failures);
        System.exit(failures > 0 ? 1 : 0);
    }

    /** Nachricht an einen Sitz (Test-Inbox). */
    private record In(GameHost.HumanSeat seat, Object msg) {
    }

    /** Antwort -> naechster State/Prompt desselben Sitzes (ms), ueber alle Spiele */
    private static final List<Long> LATENCIES = Collections.synchronizedList(new ArrayList<>());
    private static final List<Long> HEAPS = new ArrayList<>();

    private static void reportLatency() {
        List<Long> l;
        synchronized (LATENCIES) {
            l = new ArrayList<>(LATENCIES);
        }
        if (l.isEmpty()) {
            return;
        }
        Collections.sort(l);
        out("Antwort -> naechster State: n=%d p50=%d ms p95=%d ms max=%d ms", l.size(), l.get(l.size() / 2),
                l.get((int) Math.min(l.size() - 1, Math.round(l.size() * 0.95))), l.get(l.size() - 1));
    }

    /**
     * @param leave {@code prompt}: der erste Mensch verlaesst das Spiel ab Zug 4 statt einen Prompt zu beantworten;
     *              {@code bot}: ab Zug 4 waehrend eines Bot-Zugs; {@code abort}: Spiel ab Zug 4 im Bot-Zug abbrechen;
     *              {@code abortTarget}: Spiel abbrechen, waehrend der Tester ein Ziel waehlen soll (offene Eingabe);
     *              {@code abortRequiredTarget}: dasselbe nur bei Pflicht-Zielen (ohne Abbrechen-Knopf, z. B. Ausloeser)
     */
    private static boolean runGame(int nr, List<LoadedDeck> decks, TempoSettings.Preset preset, int turnCap, int humans, Random rnd,
                                   boolean verbose, String dumpJson, String scenario, boolean spectate, String leave) throws Exception {
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
        host.setTurnCap(turnCap);
        if (scenario != null) {
            host.setScenario(Scenarios.hooks(scenario));
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
        java.util.concurrent.atomic.AtomicInteger foreignPrompts = new java.util.concurrent.atomic.AtomicInteger();
        AtomicLong seatOutAt = new AtomicLong();
        Map<GameHost.HumanSeat, Driver> drivers = new java.util.LinkedHashMap<>();
        SpectatorCheck spec = spectate ? new SpectatorCheck(host) : null;
        if (spec != null) {
            host.setSpectatable(true);
        }

        for (GameHost.HumanSeat seat : host.seats()) {
            Driver drv = new Driver(host, seat, rnd, verbose, scenario);
            drivers.put(seat, drv);
            host.attach(seat, msg -> {
                if (spec != null && msg instanceof StateDto st) {
                    spec.seatState(st); // synchron, vor dem oeffentlichen State derselben seq
                }
                if ((msg instanceof StateDto || msg instanceof PromptDto) && drv.answeredAt > 0) {
                    LATENCIES.add(System.currentTimeMillis() - drv.answeredAt);
                    drv.answeredAt = 0;
                }
                if (msg instanceof PromptDto pr && !seat.playerId().equals(pr.playerId)) {
                    foreignPrompts.incrementAndGet();
                }
                if (msg instanceof Messages.SeatStatus ss && ss.conceded() && seatOutAt.get() == 0) {
                    seatOutAt.set(System.currentTimeMillis());
                }
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
        if (spec != null) {
            GameHost.SpectateResult sr = host.attachSpectator(9999L, "Zuschauer", spec, "Spike-Tisch");
            if (sr.status() != GameHost.SpectateStatus.OK) {
                out("  FEHLER: Zuschauer nicht angemeldet: %s", sr.status());
            }
            GameHost.SpectateResult seated = host.attachSpectator(1L, "Tester", msg -> {
            }, "Spike-Tisch");
            if (seated.status() != GameHost.SpectateStatus.SEATED) {
                out("  FEHLER: Sitzender als Zuschauer angenommen: %s", seated.status());
                spec.errorCount++;
            }
        }
        boolean chatted = false;
        Map<GameHost.HumanSeat, Messages.GameOver> overs = new java.util.LinkedHashMap<>();
        ChainMeter chains = new ChainMeter();
        Map<String, Integer> fxKinds = new java.util.TreeMap<>();
        int fxLeaks = 0;
        host.start();
        long t0 = System.currentTimeMillis();
        boolean stalled = false;
        Messages.GameOver over = null;
        TurnOrderCheck turnOrder = new TurnOrderCheck();
        long leftAt = 0;
        int slowTurn = 0;
        long slowSince = System.currentTimeMillis();
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
                if (d == driver && s.turn != slowTurn) {
                    long now = System.currentTimeMillis();
                    if (slowTurn > 0 && now - slowSince > 30_000) {
                        int perms = s.players.stream().mapToInt(pl -> pl.battlefield.size()).sum();
                        out("  langsamer Zug %d: %.0f s | Permanents=%d %s | Hand=%d", slowTurn, (now - slowSince) / 1000.0, perms,
                                s.players.stream().map(pl -> pl.name + ":" + pl.battlefield.size()).toList(), s.hand.size());
                    }
                    slowTurn = s.turn;
                    slowSince = now;
                }
                if (spec != null && !chatted && s.turn >= 2) {
                    chatted = host.chat(in.seat(), "Hallo Zuschauer");
                }
                if (leave != null && leftAt == 0 && d == driver && s.turn >= 4 && !d.me(s).active
                        && ("bot".equals(leave) || "abort".equals(leave))) {
                    leftAt = System.currentTimeMillis();
                    out("  -> %s im Bot-Zug %d", leave, s.turn);
                    if ("abort".equals(leave)) {
                        host.abort();
                    } else {
                        host.leave(in.seat());
                    }
                }
                if (d.convoke) {
                    d.convokeState(s);
                }
                if (d == driver) {
                    turnOrder.accept(s);
                    chains.accept(s);
                }
            } else if (msg instanceof PromptDto p) {
                if (("abortTarget".equals(leave) || ("abortRequiredTarget".equals(leave) && p.required)) && leftAt == 0 && d == driver
                        && "PICK_TARGET".equals(p.kind) && !p.defenderPick) {
                    leftAt = System.currentTimeMillis();
                    out("  -> Abbruch waehrend Zielwahl in Zug %d (Pflicht: %s)", d.state == null ? 0 : d.state.turn, p.required);
                    host.abort();
                    continue;
                }
                if ("prompt".equals(leave) && leftAt == 0 && d == driver && d.state != null && d.state.turn >= 4) {
                    leftAt = System.currentTimeMillis();
                    out("  -> verlasse waehrend Prompt %s in Zug %d", p.kind, d.state.turn);
                    host.leave(in.seat());
                    continue;
                }
                d.handle(p);
            } else if (msg instanceof Messages.GameOver g) {
                overs.put(in.seat(), g);
                if (overs.size() == drivers.size()) {
                    over = overs.get(firstSeat);
                }
            } else if (msg instanceof Messages.Events ev) {
                for (Messages.FxEvent e : ev.items()) {
                    fxKinds.merge(e.kind(), 1, Integer::sum);
                    // verdeckte Karten duerfen nur beim Besitzer mit Name/Bild ankommen
                    if (Boolean.TRUE.equals(e.hidden()) && !in.seat().playerId().equals(e.ownerId()) && (e.name() != null || e.card() != null)) {
                        fxLeaks++;
                    }
                }
            } else if (msg instanceof Messages.Toast t && verbose) {
                out("  TOAST %s", t.rich());
            }
        }
        if (dump != null) {
            dump.close();
        }
        long endAt = System.currentTimeMillis();
        long dur = endAt - t0;
        if (leftAt > 0) {
            out("  Verlassen/Abbruch: Sitz raus nach %s, Spielende nach %d ms",
                    seatOutAt.get() == 0 ? "-" : (seatOutAt.get() - leftAt) + " ms", endAt - leftAt);
        }
        if (foreignPrompts.get() > 0) {
            out("  FEHLER: %d Prompts an einen fremden Sitz", foreignPrompts.get());
        }
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
            out("  Angriff zuruecksetzen: %s", driver.resetResult == null ? "NICHT GETESTET" : driver.resetResult);
        }
        if (driver.gemstone) {
            out("  Starthand-Aktion (Gemstone): %s", driver.gemResult == null ? "FEHLER: keine Frage bekommen" : driver.gemResult);
        }
        if (driver.necro) {
            out("  Necro x5: %s", driver.necroResult == null ? "FEHLER: nicht ausgeloest (Phase " + driver.necroPhase + ")" : driver.necroResult);
        }
        if (driver.convoke) {
            out("  Convoke/X/Stopps: %s%s", driver.cvErrors.isEmpty() && driver.cvPhase >= 99 ? "OK" : "FEHLER (Phase " + driver.cvPhase + ")",
                    driver.cvNotes.isEmpty() ? "" : " | " + String.join(" | ", driver.cvNotes));
            for (String e : driver.cvErrors) {
                out("    !! %s", e);
            }
        }
        if (driver.specialPays > 0) {
            out("  Einberufen per Klick (Zufallsspiel): %d", driver.specialPays);
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
        out("  Ereignisse (events): %s%s", fxKinds.isEmpty() ? "KEINE" : fxKinds, fxLeaks > 0 ? " | FEHLER: " + fxLeaks + " verdeckte Karten an Fremde" : "");
        StatsSink stats = StatsSink.of(host.getId(), firstSeat.playerId());
        if (stats != null) {
            out("  Statistik (Sitz 1): Zuege=%d | Laender=%d | Zauber=%d (Commander %d, erster in Zug %s) | gezogen=%d | Schaden=%d (Commander %d) | Startkarten=%d",
                    stats.humanTurns(), stats.landsPlayed(), stats.spellsCast(), stats.commanderCasts(), stats.firstCommanderTurn(),
                    stats.cardsDrawn(), stats.damageDealt(), stats.commanderDamageDealt(),
                    stats.cards().values().stream().filter(c -> c.opening).count());
        }
        chains.report();
        boolean specOk = foreignPrompts.get() == 0;
        host.awaitEnd(10_000);
        if (spec != null) {
            boolean ok = spec.ok() && spec.over != null && spec.chats > 0;
            specOk &= ok;
            out("  Zuschauer: %s | %s", ok ? "OK" : "FEHLER", spec.summary());
            for (String e : spec.errors) {
                out("    !! %s", e);
            }
        }
        System.gc();
        long heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
        HEAPS.add(heap);
        out("  Nicht abgebildet (UNMAPPED): %s | automatisch: %s", host.unmappedCounts(), host.autoCounts());
        out("  Sitzordnung (UI): %s", turnOrder.seats);
        out("  Zugfolge: %s", turnOrder.sequence);
        if (turnOrder.errors > 0) {
            out("  FEHLER: Zugfolge weicht %dx von der Sitzordnung ab", turnOrder.errors);
        }
        boolean macroOk = !driver.swarm || (driver.macroResult != null && driver.macroResult.startsWith("OK")
                && driver.resetResult != null && driver.resetResult.startsWith("OK"));
        boolean gemOk = !driver.gemstone || (driver.gemResult != null && driver.gemResult.startsWith("OK"));
        boolean necroOk = !driver.necro || (driver.necroResult != null && driver.necroResult.startsWith("OK"));
        boolean convokeOk = !driver.convoke || (driver.cvErrors.isEmpty() && driver.cvPhase >= 99);
        return !stalled && specOk && gemOk && necroOk && convokeOk && fxLeaks == 0 && over != null && over.error() == null && turnOrder.errors == 0 && macroOk && replOk && allOver && promptsEverywhere;
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
        final boolean gemstone;
        final boolean necro;
        final boolean convoke;
        volatile StateDto state;
        PromptDto lastPrompt;
        final Map<String, Integer> kinds = new HashMap<>();
        /** Zeitpunkt der letzten Antwort (Latenz bis zum naechsten State) */
        volatile long answeredAt;
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
        /** "Angriff zuruecksetzen" nach dem Mehrfach-Angriff: angefordert / Ergebnis */
        boolean resetAsked;
        String resetResult;
        /** Starthand-Aktion (Szenario gemstone) */
        boolean gemAsked;
        String gemResult;
        /** Mehrfach-Aktivierung (Szenario necro): 0 = wartet auf Prioritaet, 1 = Picker offen, 2 = laeuft, 3 = fertig */
        int necroPhase;
        int necroLife, necroExile;
        String necroResult;
        /** Ersatzeffekte (Szenario dredge) */
        int replDialogs, replErrors;
        /** bis zum naechsten anderen Prompt darf keine "Dredge ...?"-Frage kommen */
        boolean replNoAsk;
        boolean replAlways;
        String replAccepted;
        boolean replAcceptedOk;
        /** Szenario convoke: 0 = Main 1 (Blaze), 1 = Blaze laeuft, 2 = Guardian gewirkt, 3 = Einberufen, 4 = wartet auf Main 2, 6-11 = F9/Stopp, 99 = fertig */
        int cvPhase;
        int cvTurn;
        Integer cvX;
        UUID cvWolf;
        int cvClicks;
        final List<String> cvErrors = new ArrayList<>();
        final List<String> cvNotes = new ArrayList<>();
        /** Einberufen per Klick in Zufallsspielen */
        int specialPays;

        Driver(GameHost host, GameHost.HumanSeat seat, Random rnd, boolean verbose, String scenario) {
            this.host = host;
            this.seat = seat;
            this.rnd = rnd;
            this.verbose = verbose;
            this.landsOnly = scenario != null;
            this.swarm = "swarm".equalsIgnoreCase(scenario);
            this.dredge = "dredge".equalsIgnoreCase(scenario);
            this.gemstone = "gemstone".equalsIgnoreCase(scenario);
            this.necro = "necro".equalsIgnoreCase(scenario);
            this.convoke = "convoke".equalsIgnoreCase(scenario);
        }

        void handle(PromptDto p) {
            if (dredge && replacement(p)) {
                return;
            }
            if (gemstone && openingHand(p)) {
                return;
            }
            if (necro && necroMacro(p)) {
                return;
            }
            if (convoke && convokeFlow(p)) {
                return;
            }
            if (!convoke && specialPay(p)) {
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
            answeredAt = System.currentTimeMillis();
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
                    if (p.messageText != null && p.messageText.contains("mana pool")) {
                        // "Pass anyway?" mit Mana im Pool: Nein fuehrt zurueck zur Prioritaet, wo wir wieder passen -
                        // endlose Schleife, sobald rep >= 3 (Nein) -> immer passen
                        return GameHost.Response.ofBool(true);
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
         * Szenario necro: Necropotence anklicken, im Picker "Pay 1 life" 5-mal ueber {@code GameHost.repeat}; sobald der
         * Stapel wieder leer ist: Leben -5 und Exil +5 pruefen. Jeder andere Prompt waehrend des Makros ist ein Fehler.
         */
        boolean necroMacro(PromptDto p) {
            StateDto s = state;
            if (s == null || necroPhase >= 3) {
                return false;
            }
            PlayerDto me = s.players.stream().filter(pl -> pl.me).findFirst().orElse(null);
            if (me == null) {
                return false;
            }
            boolean prio = "SELECT".equals(p.kind) && "priority".equals(p.mode);
            if (necroPhase == 0) {
                if (!prio || !me.active || !s.stack.isEmpty() || !"PRECOMBAT_MAIN".equals(s.step)) {
                    return false;
                }
                UUID necroId = me.battlefield.stream().filter(c -> "Necropotence".equals(c.name)).map(c -> c.id).findFirst().orElse(null);
                if (necroId == null || s.actions == null || !s.actions.contains(necroId)) {
                    return false;
                }
                necroLife = me.life;
                necroExile = me.exile == null ? 0 : me.exile.size();
                necroPhase = 1;
                lastPrompt = p;
                host.respond(seat, p.id, GameHost.Response.ofUuid(necroId));
                return true;
            }
            if (necroPhase == 1) {
                if (!"CHOOSE_ABILITY".equals(p.kind) || p.choices == null) {
                    necroResult = "FEHLER: statt Picker kam " + p.kind + " '" + trim(p.messageText, 60) + "'";
                    necroPhase = 3;
                    return false;
                }
                PromptDto.Item item = p.choices.stream().filter(i -> i.text().contains("Pay 1 life")).findFirst().orElse(null);
                if (item == null) {
                    necroResult = "FEHLER: 'Pay 1 life' nicht im Picker: " + p.choices;
                    necroPhase = 3;
                    return false;
                }
                necroPhase = 2;
                lastPrompt = p;
                if (!host.repeat(seat, p.id, UUID.fromString(item.id()), 5)) {
                    necroResult = "FEHLER: repeat() abgelehnt";
                    necroPhase = 3;
                }
                return true;
            }
            // Phase 2: laeuft
            if (prio && s.stack.isEmpty()) {
                int dl = necroLife - me.life;
                int de = (me.exile == null ? 0 : me.exile.size()) - necroExile;
                necroResult = (dl == 5 && de == 5 ? "OK " : "FEHLER ") + "Leben -" + dl + ", Exil +" + de;
                necroPhase = 3;
            } else if (!prio) {
                necroResult = "FEHLER: unerwarteter Prompt waehrend des Makros: " + p.kind + " '" + trim(p.messageText, 60) + "'";
                necroPhase = 3;
            }
            return false;
        }

        /** Zufallsspiele: Convoke-Zauber ohne freie Manaquelle -> eine Kreatur per Klick einberufen. */
        boolean specialPay(PromptDto p) {
            StateDto s = state;
            if (!"PLAY_MANA".equals(p.kind) || p.specialTargets == null || p.specialTargets.isEmpty() || s == null) {
                return false;
            }
            PlayerDto me = me(s);
            if (me != null && s.playable != null && me.battlefield.stream().anyMatch(perm -> !perm.tapped && s.playable.containsKey(perm.id))) {
                return false; // erst Laender (danach sind sie gesperrt)
            }
            lastPrompt = p;
            kinds.merge("PLAY_MANA/special", 1, Integer::sum);
            if (host.specialPay(seat, p.id, p.specialTargets.get(0))) {
                specialPays++;
                return true;
            }
            out("  !! specialPay abgelehnt fuer Prompt %d", p.id);
            return false;
        }

        /** Szenario convoke: F9 im Gegnerzug abbrechen (F3), erneut F9, per "Passen manuell" abbrechen. */
        void convokeState(StateDto s) {
            if (cvX == null && s.stack != null && !s.stack.isEmpty() && "Blaze".equals(s.stack.get(0).name)) {
                cvX = s.stack.get(0).x;
            }
            PlayerDto me = me(s);
            if (me == null || s.turn == cvTurn) {
                return;
            }
            boolean f9 = me.skips != null && me.skips.contains("myTurn");
            switch (cvPhase) {
                case 6 -> {
                    if (f9 && !me.active) {
                        cvNotes.add("F9 aktiv in Zug " + s.turn);
                        cvPhase = 7;
                        host.action(seat, "PASS_PRIORITY_CANCEL_ALL_ACTIONS", null);
                    } else if (me.active) {
                        cvFail("F9 nie im State gesehen");
                        cvPhase = 5;
                    }
                }
                case 7 -> {
                    if (me.skips == null) {
                        cvNotes.add("F3 bricht ab");
                        cvPhase = 8;
                        host.action(seat, "PASS_PRIORITY_UNTIL_MY_NEXT_TURN", null);
                    }
                }
                case 8 -> {
                    if (f9) {
                        cvPhase = 11;
                        host.setAutoPass(seat, false);
                    }
                }
                case 11 -> {
                    if (me.skips == null) {
                        cvNotes.add("Passen manuell bricht ab");
                        cvPhase = 10;
                    }
                }
                default -> {
                }
            }
        }

        private void cvFail(String msg) {
            cvErrors.add(msg);
            out("!!! Convoke-Szenario: %s", msg);
        }

        private UUID handCard(StateDto s, String name) {
            return s.hand.stream().filter(c -> name.equals(c.name)).map(c -> c.id).findFirst().orElse(null);
        }

        /**
         * Szenario convoke (siehe Klassen-Javadoc). Antwortet selbst, solange der Ablauf passt; unerwartete Prompts
         * gehen an {@link #decide}.
         */
        boolean convokeFlow(PromptDto p) {
            StateDto s = state;
            if (s == null || cvPhase >= 99) {
                return false;
            }
            PlayerDto me = me(s);
            if (me == null) {
                return false;
            }
            boolean prio = "SELECT".equals(p.kind) && "priority".equals(p.mode);
            boolean myEmpty = prio && me.active && s.stack.isEmpty();
            boolean main1 = myEmpty && "PRECOMBAT_MAIN".equals(s.step);
            if (cvPhase > 0 && s.turn != cvTurn && cvPhase < 5) {
                cvFail("Zug " + cvTurn + " vorbei in Phase " + cvPhase + " (Main-2-Stopp nicht gesehen?)");
                cvPhase = 99;
                return false;
            }
            switch (cvPhase) {
                case 0 -> {
                    if (!main1) {
                        return false;
                    }
                    cvTurn = s.turn;
                    // Kampf nur mit moeglichen Angreifern (die Szenario-Kreaturen sind in Zug 1 noch "krank")
                    String want = me.battlefield.stream().anyMatch(c -> !c.sick && !c.tapped && c.types != null && c.types.contains("CREATURE")) ? "combat" : "main2";
                    if (!want.equals(p.nextStop)) {
                        cvFail("Main 1: nextStop=" + p.nextStop + " statt " + want);
                    } else {
                        cvNotes.add("Main 1 -> " + want);
                    }
                    if (host.action(seat, "PASS_PRIORITY_UNTIL_STACK_RESOLVED", null)) {
                        cvFail("F10 bei leerem Stapel nicht abgelehnt");
                    }
                    UUID blaze = handCard(s, "Blaze");
                    if (blaze == null || s.actions == null || !s.actions.contains(blaze)) {
                        cvFail("Blaze nicht spielbar");
                        cvPhase = 99;
                        return false;
                    }
                    cvPhase = 1;
                    lastPrompt = p;
                    host.respond(seat, p.id, GameHost.Response.ofUuid(blaze));
                    return true;
                }
                case 1 -> {
                    if ("AMOUNT".equals(p.kind)) {
                        lastPrompt = p;
                        host.respond(seat, p.id, GameHost.Response.ofInt(2));
                        return true;
                    }
                    if ("PICK_TARGET".equals(p.kind) && p.targets != null && !p.targets.isEmpty()) {
                        UUID opp = s.players.stream().filter(pl -> !pl.me && !pl.lost && p.targets.contains(pl.id)).map(pl -> pl.id).findFirst().orElse(p.targets.get(0));
                        lastPrompt = p;
                        host.respond(seat, p.id, GameHost.Response.ofUuid(opp));
                        return true;
                    }
                    if (!main1) {
                        return false;
                    }
                    // Blaze ist durch
                    if (cvX == null || cvX != 2) {
                        cvFail("X auf dem Stapel: " + cvX + " statt 2");
                    } else {
                        cvNotes.add("Blaze X=2 auf dem Stapel");
                    }
                    UUID guardian = handCard(s, "Guardian of Vitu-Ghazi");
                    if (guardian == null || s.actions == null || !s.actions.contains(guardian)) {
                        cvFail("Guardian of Vitu-Ghazi nicht spielbar");
                        cvPhase = 99;
                        return false;
                    }
                    cvWolf = me.battlefield.stream().filter(c -> "Watchwolf".equals(c.name)).map(c -> c.id).findFirst().orElse(null);
                    cvPhase = 2;
                    lastPrompt = p;
                    host.respond(seat, p.id, GameHost.Response.ofUuid(guardian));
                    return true;
                }
                case 2 -> {
                    if (!"PLAY_MANA".equals(p.kind)) {
                        return false;
                    }
                    long lands = me.battlefield.stream().filter(c -> !c.tapped && c.types != null && c.types.contains("LAND")).count();
                    int n = p.specialTargets == null ? 0 : p.specialTargets.size();
                    if (p.specialBtn == null || !p.specialBtn.contains("Einberufen")) {
                        cvFail("Knopf fehlt: specialBtn=" + p.specialBtn);
                    }
                    if (n != 6 || cvWolf == null || !p.specialTargets.contains(cvWolf)) {
                        cvFail("specialTargets: " + n + " statt 6 (Watchwolf " + (cvWolf != null && n > 0 && p.specialTargets.contains(cvWolf)) + ")");
                    }
                    if (lands != 2) {
                        cvFail("ungetappte Laender " + lands + " statt 2 (Auto-Bezahlen hat von selbst begonnen?)");
                    }
                    cvNotes.add("Mana-Prompt: '" + p.specialBtn + "', " + n + " Kreaturen, " + lands + " Laender frei");
                    cvPhase = 3;
                    // Forge fragt Einberufen vor dem Mana: erst die Kreaturen (Watchwolf zuerst), dann die Laender automatisch
                    UUID pick = cvWolf != null && p.specialTargets != null && p.specialTargets.contains(cvWolf) ? cvWolf
                            : p.specialTargets == null || p.specialTargets.isEmpty() ? null : p.specialTargets.get(0);
                    lastPrompt = p;
                    if (pick == null || !host.specialPay(seat, p.id, pick)) {
                        cvFail("specialPay abgelehnt");
                        cvPhase = 99;
                        return false;
                    }
                    cvClicks++;
                    return true;
                }
                case 3 -> {
                    if ("PLAY_MANA".equals(p.kind)) {
                        lastPrompt = p;
                        if (cvClicks < 6 && p.specialTargets != null && !p.specialTargets.isEmpty()) {
                            if (!host.specialPay(seat, p.id, p.specialTargets.get(0))) {
                                cvFail("specialPay abgelehnt");
                                cvPhase = 99;
                                return false;
                            }
                            cvClicks++;
                            return true;
                        }
                        if (cvClicks < 6) {
                            cvFail("Mana-Prompt ohne einberufbare Kreaturen nach " + cvClicks + " Klicks: '" + p.messageText + "'");
                            cvPhase = 99;
                            return false;
                        }
                        host.autoPayNow(seat); // Rest mit den Laendern
                        return true;
                    }
                    if ("CHOOSE_ABILITY".equals(p.kind) || "PICK_TARGET".equals(p.kind) || "CHOOSE_CHOICE".equals(p.kind)) {
                        cvFail("Makro liess Frage durch: " + p.kind + " '" + trim(p.messageText, 60) + "'");
                        cvPhase = 99;
                        return false;
                    }
                    if (!main1) {
                        return false;
                    }
                    boolean onField = me.battlefield.stream().anyMatch(c -> "Guardian of Vitu-Ghazi".equals(c.name));
                    long tapped = me.battlefield.stream().filter(c -> c.tapped && List.of("Watchwolf", "Grizzly Bears", "Savannah Lions").contains(c.name)).count();
                    if (!onField) {
                        cvFail("Guardian nicht im Spiel");
                    }
                    if (tapped != 6 || cvClicks != 6) {
                        cvFail("eingeberufen: " + tapped + " getappt, " + cvClicks + " Klicks (erwartet 6/6)");
                    } else {
                        cvNotes.add("Guardian per 2 Laender + 6 Kreaturen");
                    }
                    if (!"main2".equals(p.nextStop)) {
                        cvFail("nach Guardian: nextStop=" + p.nextStop + " statt main2");
                    }
                    cvPhase = 4;
                    return false;
                }
                case 4 -> {
                    if (myEmpty && "POSTCOMBAT_MAIN".equals(s.step)) {
                        cvNotes.add("Main-2-Stopp (Aktionen: " + (s.actions == null ? 0 : s.actions.size()) + ")");
                        if (!"end".equals(p.nextStop)) {
                            cvFail("Main 2: nextStop=" + p.nextStop + " statt end");
                        }
                        // F9: bis zu meinem Zug passen (schliesst den Prompt selbst)
                        cvPhase = 6;
                        lastPrompt = p;
                        if (!host.action(seat, "PASS_PRIORITY_UNTIL_MY_NEXT_TURN", null)) {
                            cvFail("F9 abgelehnt");
                            cvPhase = 5;
                            return false;
                        }
                        return true;
                    }
                    return false;
                }
                case 6, 7, 8 -> {
                    if (prio && !me.active) {
                        cvFail("Prioritaet im Gegnerzug trotz F9 (Phase " + cvPhase + ", " + s.step + ")");
                        cvPhase = 99;
                    }
                    return false;
                }
                case 10 -> {
                    // "Passen manuell" + abgebrochen: Endphase eines Gegners muss halten
                    if (prio && !me.active && "END_TURN".equals(s.step)) {
                        cvNotes.add("Stopp in der Endphase eines Gegners (Zug " + s.turn + ")");
                        host.setAutoPass(seat, true);
                        cvPhase = 5;
                        return false;
                    }
                    if (me.active && s.turn != cvTurn) {
                        cvFail("kein Stopp in einer gegnerischen Endphase vor Zug " + s.turn);
                        host.setAutoPass(seat, true);
                        cvPhase = 5;
                    }
                    return false;
                }
                case 5 -> {
                    // naechster eigener Zug: gesunde, ungetappte Kreaturen -> "Zum Kampf"
                    if (!main1 || s.turn == cvTurn) {
                        return false;
                    }
                    boolean attackers = me.battlefield.stream().anyMatch(c -> !c.sick && !c.tapped && c.types != null && c.types.contains("CREATURE"));
                    String want = attackers ? "combat" : "main2";
                    if (!want.equals(p.nextStop)) {
                        cvFail("Zug " + s.turn + " Main 1: nextStop=" + p.nextStop + " statt " + want);
                    } else {
                        cvNotes.add("Zug " + s.turn + " Main 1 -> " + want);
                    }
                    cvPhase = 99;
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }

        /**
         * Szenario gemstone: die Starthand-Frage mit Ja beantworten; sobald Gemstone Caverns auf meinem Spielfeld liegt
         * (und eine Karte im Exil), ist der Test bestanden. Die Exil-Wahl beantwortet {@link #decide} (PICK_TARGET).
         */
        boolean openingHand(PromptDto p) {
            StateDto s = state;
            if (gemAsked && gemResult == null && s != null) {
                PlayerDto me = s.players.stream().filter(pl -> pl.me).findFirst().orElse(null);
                if (me != null && me.battlefield.stream().anyMatch(c -> "Gemstone Caverns".equals(c.name))) {
                    gemResult = "OK (auf dem Spielfeld, Exil=" + me.exile.size() + ")";
                } else if (s.turn >= 2) {
                    gemResult = "FEHLER: nach Zug 2 nicht auf dem Spielfeld";
                }
            }
            if (!gemAsked && "ASK".equals(p.kind) && p.messageText != null && p.messageText.startsWith("Put Gemstone Caverns")) {
                if (s != null && s.step != null) {
                    gemResult = "FEHLER: Frage kam nicht vor dem Spiel (step=" + s.step + ")";
                }
                gemAsked = true;
                lastPrompt = p;
                host.respond(seat, p.id, GameHost.Response.ofBool(true));
                return true;
            }
            return false;
        }

        /**
         * Szenario: erster Angriffs-Prompt -> alle moeglichen Angreifer per Mehrfach-Angriff auf einen Gegner;
         * zweiter Angriffs-Prompt (nach dem Makro) -> pruefen, dann per combatReset alles zuruecknehmen;
         * dritter Angriffs-Prompt -> pruefen, dass niemand mehr angreift, und bestaetigen (kein Angriff).
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
                lastPrompt = p;
                resetAsked = true;
                if (!host.combatReset(seat)) {
                    resetResult = "FEHLER: combatReset() abgelehnt";
                    host.respond(seat, p.id, GameHost.Response.ofBool(true));
                }
                return true;
            }
            if (resetAsked && resetResult == null) {
                int still = 0;
                for (var g : s.combat) {
                    still += g.attackers().size();
                }
                resetResult = (still == 0 ? "OK " : "FEHLER ") + still + " greifen noch an";
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
            if (n.startsWith("Game") || n.startsWith("forge") || n.startsWith("AI")) {
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
