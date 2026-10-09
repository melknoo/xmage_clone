package dev.magelite.game;

import dev.magelite.boot.ForgeBoot;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.stats.StatsSink;
import dev.magelite.view.ForgeViewMapper;
import dev.magelite.view.IdCodec;
import dev.magelite.view.RichText;
import dev.magelite.view.dto.Messages;
import dev.magelite.view.dto.PromptDto;
import dev.magelite.view.dto.StateDto;
import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import org.apache.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Fuehrt ein Forge-Spiel (1-4 Menschen, Rest Bots; fuer Tests auch nur Bots) aus.
 * <p>
 * Threads und Regeln:
 * <ul>
 *   <li>Ein Spiel-Thread {@code Game-ml-<id>}: {@code match.startGame} laeuft synchron darauf. Nur er fasst Forge-Objekte
 *       an; StateDto, PromptDto und Log entstehen dort.</li>
 *   <li>Client-Eingaben (WS-/Relay-Threads) rufen Forge nie direkt: sie pruefen nur und reihen Kommandos in die
 *       {@link #inbox} ein. Der Spiel-Thread fuehrt sie aus, waehrend er in einer Frage parkt ({@link #park}) oder an
 *       Safe-Points ({@link #safePoint}, vor jeder Entscheidung eines Controllers).</li>
 * </ul>
 * Das Spiel ist single-threaded: hoechstens ein offener Prompt; er gehoert genau einem Sitz ({@link #promptSeat}).
 */
public final class GameHost {

    private static final Logger LOG = Logger.getLogger(GameHost.class);
    private static final long STATE_MIN_INTERVAL_MS = 60;
    private static final int LOG_KEEP = 400;
    private static final int CHAT_KEEP = 100;
    /** Ohne Spielaenderung und ohne CPU-Last so lange -> "stuck" melden. */
    private static final long STUCK_AFTER_MS = 15000;
    /** Ab so langer Trennung duerfen die anderen Menschen einen Sitz aufgeben lassen (Dev: -Dmagelite.kickAfterMs). */
    private static final long KICK_AFTER_MS = Long.getLong("magelite.kickAfterMs", 60_000L);
    public static final int MAX_SPECTATORS = 8;
    /** Schleifen-Schutz: so viele Antworten auf eine einzige Frage, dann Autopilot */
    private static final int MAX_ANSWERS_PER_FRAME = 200;
    /**
     * Endlosschleifen-Schutz (Regel 104.4b): so viele Entscheidungen (Safe-Points) in einem Zug, dann endet das Spiel
     * unentschieden. Ein normaler Zug hat einige Dutzend, grosse Trigger-Ketten wenige Hundert.
     */
    private static final int MAX_DECISIONS_PER_TURN = 3000;

    /** Empfaenger fuer Server->Client-Nachrichten (WebSocket, Konsole, Test-Treiber). */
    public interface Sink {
        void send(Object message);
    }

    /** Belohnung pro menschlichem Sitz am Spielende (z.B. XP); null = keine. */
    public interface RewardHook {
        Object apply(GameHost host, HumanSeat seat, Messages.GameOver over);
    }

    /** Antwort des Clients auf einen Prompt. Genau ein Feld gesetzt. */
    public record Response(UUID uuid, Boolean bool, Integer integer, String string, UUID manaPlayerId, ManaColor manaType) {
        public static Response ofUuid(UUID u) {
            return new Response(u, null, null, null, null, null);
        }

        public static Response ofBool(boolean b) {
            return new Response(null, b, null, null, null, null);
        }

        public static Response ofInt(int i) {
            return new Response(null, null, i, null, null, null);
        }

        public static Response ofString(String s) {
            return new Response(null, null, null, s, null, null);
        }

        public static Response ofMana(UUID playerId, ManaColor type) {
            return new Response(null, null, null, null, playerId, type);
        }
    }

    private static final Sink NOOP = msg -> {
    };

    /** Ein menschlicher Sitz: Konto, Verbindung, Forge-Spieler/-Controller und Einstellungen pro Mensch. */
    public final class HumanSeat {
        private final int index;
        private final UUID playerId;
        private final long userId;
        private final Long deckId;
        private final LoadedDeck deck;
        private final boolean host;
        private final String name;
        private volatile Sink sink = NOOP;
        /** 0 = Client verbunden; sonst Zeitpunkt der Trennung (bzw. Erzeugung, solange sich noch niemand verbunden hat) */
        private volatile long disconnectedSince = System.currentTimeMillis();
        private volatile StateDto lastState;
        private volatile Messages.GameOver gameOver;
        private volatile boolean autoPass = true;
        private volatile boolean autoPayDefault = true;
        private volatile boolean conceded;
        /** selbst verlassen ({@link #leave}); zaehlt bei der Platzierung hinter den Ueberlebenden */
        private volatile boolean left;
        private final Deque<Long> chatTimes = new ArrayDeque<>();
        /** genommene Mulligans (Spiel-Thread) */
        int mulligans;
        /** Auto-Passen, F-Tasten, Stopps */
        final AutoPassPolicy.SeatPass pass = new AutoPassPolicy.SeatPass();
        /** laufende Mehrfach-Aktivierung (Spiel-Thread) */
        volatile RepeatMacro repeat;
        /** laufende Ersatzeffekt-Entscheidung ("Keinen anwenden" / 1-Klick), Spiel-Thread */
        volatile ReplMacro replMacro;
        /** Ersatzeffekte (Regeltext -> Kurzname), die dieses Spiel automatisch abgelehnt werden */
        final Map<String, String> replDeclineAlways = new ConcurrentHashMap<>();
        /** Antwort auf die naechste "Effekt anwenden?"-Frage (Spiel-Thread), null = fragen */
        Boolean replConfirm;
        // Forge (Spiel-Thread; gesetzt beim Anlegen des Spiels)
        private Player player;
        private HumanController controller;
        private SeatGui gui;

        private HumanSeat(int index, UUID playerId, GameSetup.SeatSpec spec, boolean host, String name) {
            this.index = index;
            this.playerId = playerId;
            this.userId = spec.userId();
            this.deckId = spec.deckId();
            this.deck = spec.deck();
            this.host = host;
            this.name = name;
        }

        void bind(Player p, HumanController c) {
            this.player = p;
            this.controller = c;
        }

        Player player() {
            return player;
        }

        HumanController controller() {
            return controller;
        }

        SeatGui gui() {
            return gui;
        }

        boolean autoPass() {
            return autoPass;
        }

        boolean autoPayDefault() {
            return autoPayDefault;
        }

        public UUID playerId() {
            return playerId;
        }

        public long userId() {
            return userId;
        }

        public Long deckId() {
            return deckId;
        }

        public LoadedDeck deck() {
            return deck;
        }

        public String name() {
            return name;
        }

        /** Gastgeber (erster Mensch): darf das Tempo stellen. */
        public boolean isHost() {
            return host;
        }

        public boolean conceded() {
            return conceded;
        }

        public boolean connected() {
            return disconnectedSince == 0;
        }

        /** Seit wann kein Client verbunden ist (ms); 0, wenn verbunden. */
        public long disconnectedForMs() {
            long since = disconnectedSince;
            return since == 0 ? 0 : System.currentTimeMillis() - since;
        }

        public Messages.GameOver gameOver() {
            return gameOver;
        }
    }

    /** "N-mal aktivieren": Quelle, Faehigkeit (Forge-ids, Text als Rueckfall), verbleibende Aktivierungen. */
    static final class RepeatMacro {
        final int sourceCardId;
        final int abilityId;
        final String abilityText;
        int remaining;

        RepeatMacro(int sourceCardId, int abilityId, String abilityText, int remaining) {
            this.sourceCardId = sourceCardId;
            this.abilityId = abilityId;
            this.abilityText = abilityText;
            this.remaining = remaining;
        }
    }

    /** Laufende Ersatzeffekt-Entscheidung: angenommen (eine Quelle) oder abgelehnt (Regeltexte). */
    static final class ReplMacro {
        final boolean accept;
        final Set<String> rules = new HashSet<>();

        ReplMacro(boolean accept) {
            this.accept = accept;
        }
    }

    /** Ergebnis von {@link #attachSpectator}: {@code replaced} = alte Verbindung desselben Nutzers (mit 4000 schliessen). */
    public enum SpectateStatus { OK, SEATED, FULL }

    public record SpectateResult(SpectateStatus status, Sink replaced) {
    }

    /** Zuschauer: zaehlt nie als Spieler (nicht in {@link #humans}, nicht im {@link GameSetup}, keine Belohnung). */
    private record Spectator(long userId, String name, Sink sink) {
    }

    private final UUID id = UUID.randomUUID();
    private final GameSetup setup;
    private final TempoSettings tempo;
    private final IdCodec ids = new IdCodec();
    private final Match match;
    private final Game game;
    private final ForgeViewMapper mapper;
    private final PromptBridge bridge = new PromptBridge(this);
    private final AutoPassPolicy policy = new AutoPassPolicy();
    /** menschliche Sitze nach Spieler-id, in Tischreihenfolge; nach dem Konstruktor nur gelesen */
    private final Map<UUID, HumanSeat> humans = new LinkedHashMap<>();
    private final HumanSeat firstHuman;
    private final Map<UUID, String> deckNames = new LinkedHashMap<>();
    private final List<Messages.Seat> seats = new ArrayList<>();
    private final Map<UUID, List<String>> commanders = new LinkedHashMap<>();

    /** Kommandos fuer den Spiel-Thread (Antworten, Aufgeben, Abbruch) */
    private final LinkedBlockingQueue<Runnable> inbox = new LinkedBlockingQueue<>();
    /** offene Fragen (Spiel-Thread), oberste = aktuelle */
    private final Deque<PromptBridge.Frame> frames = new ArrayDeque<>();
    /** offener Prompt; Antworten raeumen ihn per compareAndSet (genau eine Antwort pro Prompt) */
    private final AtomicReference<PromptDto> openPrompt = new AtomicReference<>();
    private volatile HumanSeat promptSeat;
    private volatile PromptBridge.Frame promptFrame;
    /** Spiel-Thread parkt in einer Frage (fuer Aktivitaet/Wachhund) */
    private volatile boolean parked;

    private final Set<Spectator> spectators = ConcurrentHashMap.newKeySet();
    /** Reihenfolge "hello vor State": Zuschauer anmelden und oeffentlichen State verteilen nur unter dieser Sperre */
    private final Object specLock = new Object();
    private volatile StateDto publicState;
    private volatile boolean spectatable;
    private volatile String tableName;
    private volatile UUID viewpoint;
    private long lastPublicErrorAt;
    private volatile Messages.GameOver spectatorOver;

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "magelite-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** Spielereignisse fuer Animationen (Spiel-Thread fuellt, vor dem naechsten State bzw. per Wachhund geleert) */
    private final ConcurrentLinkedQueue<Messages.FxEvent> fx = new ConcurrentLinkedQueue<>();
    private final AtomicInteger fxSize = new AtomicInteger();
    private volatile long lastFxAt;
    private static final int FX_MAX = 200;
    private static final long FX_FLUSH_AFTER_MS = 150;
    private final AtomicLong stateSeq = new AtomicLong();
    private final AtomicLong promptSeq = new AtomicLong();
    private final Deque<Messages.LogEntry> logTail = new ArrayDeque<>();
    private final Deque<Messages.ChatEntry> chatTail = new ArrayDeque<>();
    private final List<Integer> eliminationOrder = new ArrayList<>();
    private final Map<Integer, Integer> eliminatedTurn = new LinkedHashMap<>();
    private volatile Player thinking;
    /** Bot entscheidet gerade (seit wann); "denkt" wird erst nach 300 ms gemeldet */
    private volatile Player deciding;
    private volatile long decidingSince;
    /** Pause vor der naechsten Entscheidung (nach einer sichtbaren Bot-Aktion), Spiel-Thread */
    private int pendingPauseMs;
    /** aufgedeckte/angesehene Karten (Spiel-Thread) */
    private final List<Reveal> reveals = new ArrayList<>();
    private volatile int turn;
    private volatile Messages.GameOver gameOver;
    private volatile Consumer<GameHost> onFinished;
    private volatile RewardHook rewardHook;
    private long lastStateAt;
    private boolean stateDirty;
    private volatile Thread gameThread;
    private volatile long startedAt;
    private volatile int turnCap;
    private volatile ScenarioHooks scenario;
    private volatile boolean aborting;
    private volatile long lastProgressAt = System.currentTimeMillis();
    private long lastCpuNs = -1;
    private long lastCpuAt;
    private int ticks;
    private int decisionTurn;
    private int decisionsThisTurn;

    private GameHost(GameSetup setup, List<GameSetup.SeatSpec> specs, TempoSettings.Preset preset, List<String> botProfiles) {
        this.setup = setup;
        this.tempo = new TempoSettings(preset);
        Set<String> usedNames = new HashSet<>();
        List<RegisteredPlayer> regs = new ArrayList<>();
        HumanSeat first = null;
        int index = 0;
        for (GameSetup.SeatSpec spec : specs) {
            UUID wire = ids.player(index); // Forge vergibt Spieler-ids 0..n-1 in Anmeldereihenfolge (geprueft unten)
            String name;
            LobbyPlayer lobby;
            if (spec.human()) {
                name = uniqueName(spec.name() == null || spec.name().isBlank() ? "Spieler" : spec.name(), usedNames);
                HumanSeat seat = new HumanSeat(index, wire, spec, first == null, name);
                humans.put(wire, seat);
                if (first == null) {
                    first = seat;
                }
                lobby = new HumanController.Lobby(name, this, seat);
            } else {
                name = botName(spec.deck(), usedNames);
                String profile = botProfiles == null || botProfiles.isEmpty() ? ForgeBoot.AI_PROFILE
                        : botProfiles.get(Math.min(index, botProfiles.size() - 1));
                lobby = new ForgeBot.Lobby(name, this, profile);
            }
            RegisteredPlayer rp = RegisteredPlayer.forCommander(spec.deck().newDeck());
            rp.setPlayer(lobby);
            regs.add(rp);
            deckNames.put(wire, spec.deck().name());
            commanders.put(wire, spec.deck().commanders());
            seats.add(new Messages.Seat(wire, name, spec.human(), spec.deck().name(), spec.deck().commanders()));
            index++;
        }
        firstHuman = first;

        GameRules rules = new GameRules(GameType.Commander);
        rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        rules.setGamesPerMatch(1);
        match = new Match(rules, regs, "MageLite");
        game = match.createGame();
        game.AI_TIMEOUT = Math.max(1, tempo.thinkSecs());
        mapper = new ForgeViewMapper(game, ids);

        for (HumanSeat seat : humans.values()) {
            if (seat.player == null || seat.player.getId() != seat.index) {
                throw new IllegalStateException("Forge-Spieler-id passt nicht zum Sitz " + seat.name);
            }
            SeatGui gui = new SeatGui(this, seat);
            seat.gui = gui;
            seat.controller.setGui(gui);
            gui.setGameView(null);
            gui.setGameView(game.getView());
            gui.setOriginalGameController(seat.player.getView(), seat.controller);
        }
        for (Player p : game.getPlayers()) {
            p.updateOpponentsForView();
        }
        game.getGameLog().addObserver((o, arg) -> onLogAdded());
        game.subscribeToEvents(new ForgeEvents(this));
        for (HumanSeat seat : humans.values()) {
            StatsSink.register(id, seat.playerId);
        }
    }

    public static GameHost create(GameSetup setup) {
        return new GameHost(setup, setup.seats(), setup.tempo(), null);
    }

    /** Spiel nur mit Bots (Spikes, Bot-Arena); kein {@link GameSetup}. */
    public static GameHost createBots(List<LoadedDeck> decks, TempoSettings.Preset preset) {
        return createBots(decks, preset, null);
    }

    /** @param profiles KI-Profil je Sitz (Forge-Profilname, z.B. "Default", "Reckless"); null = MageLite-Profil */
    public static GameHost createBots(List<LoadedDeck> decks, TempoSettings.Preset preset, List<String> profiles) {
        List<GameSetup.SeatSpec> specs = decks.stream().map(GameSetup.SeatSpec::bot).toList();
        return new GameHost(null, specs, preset, profiles);
    }

    private static String botName(LoadedDeck deck, Set<String> used) {
        String base = deck.commanders().isEmpty() ? deck.name() : deck.commanders().get(0);
        int comma = base.indexOf(',');
        if (comma > 0) {
            base = base.substring(0, comma);
        }
        return uniqueName(base, used);
    }

    private static String uniqueName(String base, Set<String> used) {
        String name = base;
        int n = 2;
        while (!used.add(name)) {
            name = base + " " + n++;
        }
        return name;
    }

    // ------------------------------------------------------------------ Paket-intern (Spiel-Thread)

    Game game() {
        return game;
    }

    ForgeViewMapper mapper() {
        return mapper;
    }

    PromptBridge bridge() {
        return bridge;
    }

    AutoPassPolicy policy() {
        return policy;
    }

    boolean isGameThread() {
        return Thread.currentThread() == gameThread;
    }

    PromptBridge.Frame topFrame() {
        return frames.peek();
    }

    /** offene Fragen (Spiel-Thread), oberste zuerst */
    Iterable<PromptBridge.Frame> framesView() {
        return frames;
    }

    // ------------------------------------------------------------------ Lebenszyklus

    public UUID getId() {
        return id;
    }

    /** Spieler-id des ersten Menschen (Gastgeber); fuer Szenarien und Tests. */
    public UUID getHumanId() {
        return firstHuman.playerId;
    }

    public Collection<HumanSeat> seats() {
        return Collections.unmodifiableCollection(humans.values());
    }

    public HumanSeat firstSeat() {
        return firstHuman;
    }

    public Optional<HumanSeat> seatOf(long userId) {
        return humans.values().stream().filter(s -> s.userId == userId).findFirst();
    }

    public Optional<HumanSeat> seatOfPlayer(UUID playerId) {
        return Optional.ofNullable(humans.get(playerId));
    }

    /** Forge-Spiel; nur auf dem Spiel-Thread anfassen. */
    public Game getGame() {
        return game;
    }

    public TempoSettings getTempo() {
        return tempo;
    }

    public Messages.GameOver getGameOver() {
        return gameOver;
    }

    public boolean isRunning() {
        return gameThread != null && gameThread.isAlive();
    }

    public void setOnFinished(Consumer<GameHost> onFinished) {
        this.onFinished = onFinished;
    }

    /** Test-Szenario (Dev-Modus, Spikes); vor {@link #start()} setzen. */
    public void setScenario(ScenarioHooks scenario) {
        this.scenario = scenario;
    }

    ScenarioHooks scenario() {
        return scenario;
    }

    /** Forge-Spieler zu einer Wire-id (nur Spiel-Thread, z.B. Szenarien); null wenn unbekannt. */
    public Player forgePlayer(UUID wireId) {
        IdCodec.Decoded d = ids.decode(wireId);
        return d == null || d.kind() != IdCodec.Kind.PLAYER ? null : game.getPlayer(d.id());
    }

    /** Wire-id eines Forge-Spielers. */
    public UUID wireId(Player p) {
        return ids.player(p.getId());
    }

    /** Menschlicher Sitz eines Forge-Spielers (null = Bot). */
    HumanSeat seatOf(Player p) {
        return p == null ? null : humans.get(ids.player(p.getId()));
    }

    /** Spiel endet als Unentschieden, sobald dieser Zug vorbei ist (0 = aus). Fuer Spikes. */
    public void setTurnCap(int turnCap) {
        this.turnCap = turnCap;
    }

    public void setAutoPass(HumanSeat seat, boolean autoPass) {
        boolean wasOn = seat.autoPass;
        seat.autoPass = autoPass;
        if (wasOn && !autoPass) {
            // "Passen manuell" beendet auch laufendes F-Tasten-Passen. Nur beim Umschalten: der Client schickt die
            // Einstellung bei jedem Reconnect erneut.
            cancelSkip(seat);
        }
    }

    /** Stopps: Upkeep der Gegner halten; anhalten, sobald ein fremdes Stapelobjekt mich/meins anvisiert. */
    public void setStops(HumanSeat seat, Boolean stopOppUpkeep, Boolean stopOnTargeted) {
        if (stopOppUpkeep != null) {
            seat.pass.stopOppUpkeep = stopOppUpkeep;
        }
        if (stopOnTargeted != null) {
            seat.pass.stopOnTargeted = stopOnTargeted;
        }
    }

    private void cancelSkip(HumanSeat seat) {
        inbox.add(() -> {
            seat.pass.skip = AutoPassPolicy.SkipMode.NONE;
            seat.pass.passedSigs.clear();
            refreshState();
        });
    }

    /** Spiel-Thread: State sofort neu senden, wenn gerade niemand gefragt wird (sonst kommt er mit dem naechsten). */
    private void refreshState() {
        if (frames.isEmpty()) {
            stateDirty = true;
        } else {
            sendState(null, PromptBridge.StateMode.NONE);
        }
    }

    /** Verbindet einen Client mit einem Sitz und schickt den aktuellen Stand (Resync nach Reconnect). */
    public synchronized void attach(HumanSeat seat, Sink newSink) {
        seat.sink = newSink;
        seat.disconnectedSince = 0;
        newSink.send(hello(seat));
        if (seat.conceded) {
            newSink.send(new Messages.SeatStatus(true));
        }
        synchronized (logTail) {
            if (!logTail.isEmpty()) {
                newSink.send(new Messages.Log(new ArrayList<>(logTail)));
            }
        }
        synchronized (chatTail) {
            if (!chatTail.isEmpty()) {
                newSink.send(new Messages.Chat(new ArrayList<>(chatTail)));
            }
        }
        StateDto s = seat.lastState;
        if (s != null) {
            newSink.send(s);
        }
        PromptDto p = openPrompt.get();
        if (p != null && promptSeat == seat) {
            newSink.send(p);
        }
        Messages.GameOver over = seat.gameOver;
        if (over != null) {
            newSink.send(over);
        }
        if (humans.size() > 1 || !spectators.isEmpty()) {
            broadcastSeats();
        }
    }

    // ------------------------------------------------------------------ Zuschauer

    public void setSpectatable(boolean on) {
        this.spectatable = on;
    }

    public boolean isSpectatable() {
        return spectatable;
    }

    public int spectatorCount() {
        return spectators.size();
    }

    /**
     * Meldet einen Zuschauer an: hello, Spiel- und Chat-Verlauf, gecachter oeffentlicher State, ggf. gameOver (ohne
     * Belohnung), Sitz-Status. Beruehrt nie das Spiel. Ein Nutzer schaut hoechstens einmal zu: eine neue Verbindung
     * ersetzt die alte ({@code replaced}).
     */
    public SpectateResult attachSpectator(long userId, String name, Sink sink, String tableName) {
        Sink replaced = null;
        synchronized (specLock) {
            if (seatOf(userId).isPresent()) {
                return new SpectateResult(SpectateStatus.SEATED, null);
            }
            Spectator old = spectators.stream().filter(x -> x.userId() == userId).findFirst().orElse(null);
            if (old == null && spectators.size() >= MAX_SPECTATORS) {
                return new SpectateResult(SpectateStatus.FULL, null);
            }
            if (old != null) {
                spectators.remove(old);
                replaced = old.sink();
            }
            if (tableName != null) {
                this.tableName = tableName;
            }
            sink.send(Messages.Hello.spectator(id, seats, tempo.preset().name(), viewpointId(), this.tableName));
            synchronized (logTail) {
                if (!logTail.isEmpty()) {
                    sink.send(new Messages.Log(new ArrayList<>(logTail)));
                }
            }
            synchronized (chatTail) {
                if (!chatTail.isEmpty()) {
                    sink.send(new Messages.Chat(new ArrayList<>(chatTail)));
                }
            }
            StateDto pub = publicState;
            if (pub != null) {
                sink.send(pub);
            }
            Messages.GameOver over = spectatorOver;
            if (over != null) {
                sink.send(over);
            }
            spectators.add(new Spectator(userId, name == null || name.isBlank() ? "Zuschauer" : name, sink));
        }
        LOG.info("Zuschauer " + name + " schaut Spiel " + id + " zu (" + spectators.size() + ")");
        broadcastSeats();
        return new SpectateResult(SpectateStatus.OK, replaced);
    }

    public void detachSpectator(Sink sink) {
        if (spectators.removeIf(x -> x.sink() == sink)) {
            broadcastSeats();
        }
    }

    private UUID viewpointId() {
        UUID v = viewpoint;
        if (v != null) {
            return v;
        }
        for (HumanSeat s : humans.values()) {
            if (!s.conceded) {
                return s.playerId;
            }
        }
        return firstHuman == null ? null : firstHuman.playerId;
    }

    private void sendSpectators(Object msg) {
        for (Spectator x : spectators) {
            try {
                x.sink().send(msg);
            } catch (Throwable e) {
                LOG.warn("Senden an Zuschauer fehlgeschlagen: " + e);
            }
        }
    }

    /** An alle Menschen und alle Zuschauer (nur oeffentliche Nachrichten: Log, Chat, Status, Sitz-Status). */
    private void emitPublic(Object msg) {
        emit(msg);
        sendSpectators(msg);
    }

    /** Chat-Nachricht eines Menschen an alle am Tisch. Reine Host-Ebene, der Spiel-Thread ist nicht beteiligt. */
    public boolean chat(HumanSeat seat, String text) {
        String clean = ChatText.clean(text);
        if (clean == null) {
            return false;
        }
        synchronized (seat.chatTimes) {
            if (!ChatText.allow(seat.chatTimes, System.currentTimeMillis())) {
                send(seat, new Messages.Toast("info", RichText.parse("Langsamer – höchstens " + ChatText.RATE_N + " Nachrichten in "
                        + (ChatText.RATE_MS / 1000) + " s.")));
                return false;
            }
        }
        Messages.ChatEntry entry = new Messages.ChatEntry(System.currentTimeMillis(), seat.playerId, seat.name(), clean);
        synchronized (chatTail) {
            chatTail.addLast(entry);
            while (chatTail.size() > CHAT_KEEP) {
                chatTail.pollFirst();
            }
        }
        emitPublic(new Messages.Chat(List.of(entry)));
        return true;
    }

    public void detach(HumanSeat seat, Sink oldSink) {
        if (seat.sink == oldSink) {
            seat.sink = NOOP;
            seat.disconnectedSince = System.currentTimeMillis();
            if (humans.size() > 1 || !spectators.isEmpty()) {
                broadcastSeats();
            }
        }
    }

    private void broadcastSeats() {
        List<Messages.SeatConn> list = new ArrayList<>();
        for (HumanSeat s : humans.values()) {
            list.add(new Messages.SeatConn(s.playerId, s.connected(), s.disconnectedForMs(), s.conceded));
        }
        List<String> names = spectators.stream().map(Spectator::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        emitPublic(new Messages.SeatsStatus(list, KICK_AFTER_MS, names));
    }

    /** Ein verbundener Mensch laesst einen lange getrennten Mitspieler aufgeben. */
    public boolean kick(HumanSeat by, UUID targetPlayerId) {
        HumanSeat target = humans.get(targetPlayerId);
        if (target == null || target == by || target.conceded || by.conceded || !by.connected()
                || target.connected() || target.disconnectedForMs() < KICK_AFTER_MS) {
            return false;
        }
        LOG.info(target.name() + " wird nach " + (target.disconnectedForMs() / 1000) + " s Trennung von " + by.name() + " aufgegeben");
        leave(target);
        broadcastSeats();
        return true;
    }

    /** Seit wann kein (noch mitspielender) Mensch verbunden ist, in ms; 0, sobald einer verbunden ist. */
    public long disconnectedForMs() {
        long min = Long.MAX_VALUE;
        boolean any = false;
        for (HumanSeat s : humans.values()) {
            if (s.conceded) {
                continue;
            }
            any = true;
            long d = s.disconnectedForMs();
            if (d == 0) {
                return 0;
            }
            min = Math.min(min, d);
        }
        return any ? min : System.currentTimeMillis() - startedAt;
    }

    public Messages.Hello hello(HumanSeat seat) {
        return new Messages.Hello(id, seat.playerId, seats, tempo.preset().name(), seat.host);
    }

    public synchronized void start() {
        if (gameThread != null) {
            return;
        }
        startedAt = System.currentTimeMillis();
        gameThread = new Thread(this::runGame, "Game-ml-" + id.toString().substring(0, 8));
        gameThread.start();
        watchdog.scheduleWithFixedDelay(this::watchdogTick, 500, 500, TimeUnit.MILLISECONDS);
    }

    private void runGame() {
        String error = null;
        try {
            ScenarioHooks sc = scenario;
            match.startGame(game, sc == null ? null : () -> sc.startGame(this));
        } catch (GameEnded e) {
            // Spielende waehrend einer Eingabe: regulaer, Ergebnis steht schon im Spiel
        } catch (Throwable e) {
            LOG.error("Spiel abgebrochen", e);
            error = e.toString();
            try {
                if (!game.isGameOver()) {
                    game.setGameOver(GameEndReason.Draw);
                }
            } catch (Throwable ignored) {
                // Spiel ist ohnehin kaputt
            }
        }
        try {
            trackEliminations();
            sendState(null, PromptBridge.StateMode.NONE);
            Messages.GameOver base = buildGameOver(error);
            gameOver = base;
            RewardHook hook = rewardHook;
            for (HumanSeat seat : humans.values()) {
                Messages.GameOver mine = base;
                if (hook != null) {
                    try {
                        Object reward = hook.apply(this, seat, base);
                        if (reward != null) {
                            mine = new Messages.GameOver(base.winnerId(), base.result(), base.placements(), base.turns(),
                                    base.durationMs(), reward, base.error());
                        }
                    } catch (Throwable e) {
                        LOG.error("Belohnung fehlgeschlagen (" + seat.name() + ")", e);
                    }
                }
                seat.gameOver = mine;
                send(seat, mine);
            }
            synchronized (specLock) {
                sendSpectators(base);
                spectatorOver = base;
            }
        } catch (Throwable e) {
            LOG.error("Spielende-Auswertung fehlgeschlagen", e);
        } finally {
            Consumer<GameHost> cb = onFinished;
            if (cb != null) {
                try {
                    cb.accept(this);
                } catch (Throwable e) {
                    LOG.error("onFinished fehlgeschlagen", e);
                }
            }
            for (HumanSeat s : humans.values()) {
                try {
                    s.gui.afterGameEnd();
                } catch (Throwable ignored) {
                    // nur Timer aufraeumen
                }
            }
            watchdog.shutdownNow();
        }
    }

    /** Beendet das Spiel (Unentschieden), z.B. Admin, verwaistes Spiel, alle Menschen weg. */
    public void abort() {
        aborting = true;
        for (HumanSeat s : humans.values()) {
            s.conceded = true;
        }
        inbox.add(() -> {
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        });
    }

    /**
     * Ein Mensch verlaesst das Spiel: nur sein Sitz gibt auf (offene Fragen beantwortet der Autopilot). Sind danach
     * keine Menschen mehr im Spiel, endet es.
     */
    public void leave(HumanSeat seat) {
        if (seat.conceded) {
            return;
        }
        seat.conceded = true;
        seat.left = true;
        closePromptOf(seat);
        send(seat, new Messages.SeatStatus(true));
        boolean humansLeft = humans.values().stream().anyMatch(s -> !s.conceded);
        inbox.add(() -> {
            if (seat.player != null && seat.player.isInGame() && !game.isGameOver()) {
                addLog("INFO", seat.name() + " gibt auf");
                seat.controller.concede();
            }
        });
        if (!humansLeft) {
            abort();
        }
    }

    public boolean awaitEnd(long timeoutMs) throws InterruptedException {
        Thread t = gameThread;
        if (t == null) {
            return true;
        }
        t.join(timeoutMs);
        return !t.isAlive();
    }

    // ------------------------------------------------------------------ Client -> Engine

    /** Antwort auf den offenen Prompt. Nur vom Besitzer; ignoriert veraltete promptIds (Doppelklicks). */
    public boolean respond(HumanSeat seat, long promptId, Response r) {
        PromptDto p = openPrompt.get();
        PromptBridge.Frame f = promptFrame;
        if (p == null || p.id != promptId || promptSeat != seat || !openPrompt.compareAndSet(p, null)) {
            return false;
        }
        send(seat, new Messages.PromptClosed(promptId));
        notifyOthers(seat, null);
        inbox.add(() -> answer(f, r));
        return true;
    }

    /** Spiel-Thread: Antwort an die Frage geben, falls sie noch die aktuelle ist. */
    private void answer(PromptBridge.Frame f, Response r) {
        if (f == null || frames.peek() != f || f.done()) {
            return;
        }
        try {
            if (++f.answers > MAX_ANSWERS_PER_FRAME) {
                // Antwort-Schleife (Client und Forge drehen sich im Kreis): sicher beenden statt ewig zu haengen
                if (f.answers == MAX_ANSWERS_PER_FRAME + 1) {
                    bridge.unmapped(f.seat, "loop", f.describe() + " nach " + MAX_ANSWERS_PER_FRAME + " Antworten, letzte: " + r);
                }
                f.autopilot();
                return;
            }
            f.answer(r);
        } finally {
            if (!f.done()) {
                f.dirty = true;
            }
        }
    }

    /**
     * Mehrfach-Angriff/-Block: {@code ids} greifen {@code target} an (Angriffs-Prompt) bzw. blocken den Angreifer
     * {@code target} (Block-Prompt). Danach kommt ein neuer Prompt (zum Bestaetigen).
     */
    public boolean combat(HumanSeat seat, List<UUID> ids, UUID target) {
        PromptDto p = openPrompt.get();
        PromptBridge.Frame f = promptFrame;
        if (p == null || promptSeat != seat) {
            return false;
        }
        boolean attack = "attackers".equals(p.mode);
        if (!"SELECT".equals(p.kind) || target == null || ids == null || (!attack && !"blockers".equals(p.mode))) {
            send(seat, p); // Client hat den Prompt schon als beantwortet markiert -> erneut zustellen
            return false;
        }
        List<UUID> possible = attack ? p.possibleAttackers : p.possibleBlockers;
        List<UUID> list = ids.stream().distinct().filter(id -> possible != null && possible.contains(id)).toList();
        if (list.isEmpty() || !openPrompt.compareAndSet(p, null)) {
            if (openPrompt.get() == p) {
                send(seat, p);
            }
            return false;
        }
        send(seat, new Messages.PromptClosed(p.id));
        notifyOthers(seat, null);
        String mode = p.mode;
        inbox.add(() -> {
            if (f instanceof PromptBridge.InputFrame inf && frames.peek() == f && !f.done() && inf.isCombat(mode)) {
                inf.combatMacro(list, target);
            }
            if (f != null && !f.done()) {
                f.dirty = true;
            }
        });
        return true;
    }

    /** "Angriff zuruecksetzen": alle eigenen Angreifer zurueck, solange der Angriff nicht bestaetigt ist. */
    public boolean combatReset(HumanSeat seat) {
        PromptDto p = openPrompt.get();
        PromptBridge.Frame f = promptFrame;
        if (p == null || promptSeat != seat) {
            return false;
        }
        boolean ok = ("SELECT".equals(p.kind) && "attackers".equals(p.mode)) || ("PICK_TARGET".equals(p.kind) && p.defenderPick);
        if (!ok || !openPrompt.compareAndSet(p, null)) {
            if (openPrompt.get() == p) {
                send(seat, p);
            }
            return false;
        }
        send(seat, new Messages.PromptClosed(p.id));
        notifyOthers(seat, null);
        inbox.add(() -> {
            if (f instanceof PromptBridge.InputFrame inf && frames.peek() == f && !f.done()) {
                inf.combatReset();
            }
            if (f != null && !f.done()) {
                f.dirty = true;
            }
        });
        return true;
    }

    /**
     * "N-mal aktivieren" (z.B. Necropotence "Pay 1 life"): Antwort auf den offenen {@code CHOOSE_ABILITY}-Prompt mit
     * {@code abilityId}, danach {@code times - 1} weitere Aktivierungen derselben Faehigkeit ({@link HumanController}).
     * Ziele, Fragen oder fremde Stapelobjekte beenden die Wiederholung.
     */
    public boolean repeat(HumanSeat seat, long promptId, UUID abilityId, int times) {
        PromptDto p = openPrompt.get();
        PromptBridge.Frame f = promptFrame;
        if (p == null || p.id != promptId || promptSeat != seat) {
            return false;
        }
        PromptDto.Item item = null;
        if ("CHOOSE_ABILITY".equals(p.kind) && p.choices != null && abilityId != null) {
            for (PromptDto.Item i : p.choices) {
                if (abilityId.toString().equals(i.id())) {
                    item = i;
                }
            }
        }
        IdCodec.Decoded src = p.sourceId == null ? null : ids.decode(p.sourceId);
        IdCodec.Decoded ab = ids.decode(abilityId);
        if (item == null || src == null || ab == null || src.kind() != IdCodec.Kind.CARD) {
            send(seat, p);
            return false;
        }
        if (!openPrompt.compareAndSet(p, null)) {
            if (openPrompt.get() == p) {
                send(seat, p);
            }
            return false;
        }
        int n = Math.max(1, Math.min(20, times));
        String text = item.text();
        send(seat, new Messages.PromptClosed(p.id));
        notifyOthers(seat, null);
        inbox.add(() -> {
            seat.repeat = n > 1 ? new RepeatMacro(src.id(), ab.id(), text, n - 1) : null;
            answer(f, Response.ofUuid(abilityId));
        });
        return true;
    }

    /** Einberufen per Klick: beim offenen Mana-Prompt die Kreatur {@code permId} tappen. */
    public boolean specialPay(HumanSeat seat, long promptId, UUID permId) {
        PromptDto p = openPrompt.get();
        PromptBridge.Frame f = promptFrame;
        if (p == null || promptSeat != seat) {
            return false;
        }
        if (p.id != promptId || !"PLAY_MANA".equals(p.kind) || permId == null || p.specialTargets == null
                || !p.specialTargets.contains(permId) || !openPrompt.compareAndSet(p, null)) {
            if (openPrompt.get() == p) {
                send(seat, p);
            }
            return false;
        }
        send(seat, new Messages.PromptClosed(p.id));
        notifyOthers(seat, null);
        inbox.add(() -> answer(f, Response.ofUuid(permId)));
        return true;
    }

    /**
     * Ersatzeffekt-Wahl mit Gruppen: {@code accept} = Effekt {@code key} anwenden (seine Rueckfrage beantwortet die
     * Engine mit Ja), {@code acceptGroup} = erste Quelle der gleichnamigen Gruppe {@code key} (Regeltext),
     * {@code decline} = alle optionalen Effekte ablehnen (Wahl und Nein-Kette automatisch), mit {@code always} auch in
     * allen weiteren Ereignissen dieses Spiels.
     */
    public boolean replacement(HumanSeat seat, String mode, String key, boolean always) {
        PromptDto p = openPrompt.get();
        PromptBridge.Frame f = promptFrame;
        if (p == null || promptSeat != seat) {
            return false;
        }
        List<PromptDto.ReplGroup> groups = "CHOOSE_CHOICE".equals(p.kind) && p.choice != null ? p.choice.groups : null;
        ReplMacro m = null;
        String answer = null;
        Boolean confirm = null;
        if (groups != null && "accept".equals(mode) && key != null) {
            for (PromptDto.ReplGroup g : groups) {
                for (PromptDto.ReplSource s : g.sources()) {
                    if (key.equals(s.key())) {
                        answer = key;
                        confirm = g.optional() ? Boolean.TRUE : null;
                    }
                }
            }
        } else if (groups != null && "acceptGroup".equals(mode) && key != null) {
            for (PromptDto.ReplGroup g : groups) {
                if (key.equals(g.rule()) && g.uniform() && !g.sources().isEmpty()) {
                    answer = g.sources().get(0).key();
                    confirm = g.optional() ? Boolean.TRUE : null;
                }
            }
        } else if (groups != null && "decline".equals(mode)) {
            m = new ReplMacro(false);
            for (PromptDto.ReplGroup g : groups) {
                if (!g.optional()) {
                    continue;
                }
                m.rules.add(g.rule());
                if (always) {
                    seat.replDeclineAlways.put(g.rule(), g.label());
                }
                if (answer == null) {
                    answer = g.sources().get(0).key();
                    confirm = Boolean.FALSE;
                }
            }
        }
        if (answer == null || !openPrompt.compareAndSet(p, null)) {
            if (openPrompt.get() == p) {
                send(seat, p);
            }
            return false;
        }
        ReplMacro macro = m;
        String a = answer;
        Boolean c = confirm;
        send(seat, new Messages.PromptClosed(p.id));
        notifyOthers(seat, null);
        inbox.add(() -> {
            seat.replMacro = macro;
            seat.replConfirm = c;
            answer(f, Response.ofString(a));
        });
        return true;
    }

    /** "Fuer dieses Spiel merken" zuruecknehmen. */
    public void resetReplacementDeclines(HumanSeat seat) {
        seat.replDeclineAlways.clear();
        StateDto last = seat.lastState;
        if (last != null) {
            last.replDeclines = null; // Client leert seine Anzeige selbst; der naechste State kommt ohne
        }
    }

    /**
     * F-Tasten und Einstellungen (XMage-{@code PlayerAction}-Namen, kein Undo/Rollback/Cheat). F-Tasten schliessen den
     * offenen Prioritaets-Prompt und passen; F10 bei leerem Stapel wird abgelehnt.
     */
    public boolean action(HumanSeat seat, String actionName, Object data) {
        AutoPassPolicy.SkipMode skip = AutoPassPolicy.SkipMode.ofAction(actionName);
        if (skip != null) {
            if (skip == AutoPassPolicy.SkipMode.STACK_RESOLVED) {
                StateDto last = seat.lastState;
                if (last == null || last.stack == null || last.stack.isEmpty()) {
                    return false;
                }
            }
            closePriorityPromptOf(seat);
            inbox.add(() -> {
                if (game.isGameOver()) {
                    return;
                }
                policy.startSkip(seat.pass, skip, game);
                if (!bridge.passPriority(seat)) {
                    refreshState();
                }
            });
            return true;
        }
        switch (actionName) {
            case "PASS_PRIORITY_CANCEL_ALL_ACTIONS" -> {
                cancelSkip(seat);
                return true;
            }
            case "HOLD_PRIORITY" -> {
                seat.pass.hold = true;
                return true;
            }
            case "UNHOLD_PRIORITY" -> {
                seat.pass.hold = false;
                return true;
            }
            case "RESET_AUTO_SELECT_REPLACEMENT_EFFECTS" -> {
                resetReplacementDeclines(seat);
                return true;
            }
            case "TRIGGER_AUTO_ORDER_ABILITY_FIRST", "TRIGGER_AUTO_ORDER_ABILITY_LAST", "TRIGGER_AUTO_ORDER_NAME_FIRST",
                 "TRIGGER_AUTO_ORDER_NAME_LAST", "TRIGGER_AUTO_ORDER_RESET_ALL", "REQUEST_AUTO_ANSWER_ID_YES",
                 "REQUEST_AUTO_ANSWER_ID_NO", "REQUEST_AUTO_ANSWER_TEXT_YES", "REQUEST_AUTO_ANSWER_TEXT_NO",
                 "REQUEST_AUTO_ANSWER_RESET_ALL", "MANA_AUTO_PAYMENT_RESTRICTED_ON", "MANA_AUTO_PAYMENT_RESTRICTED_OFF",
                 "USE_FIRST_MANA_ABILITY_ON", "USE_FIRST_MANA_ABILITY_OFF" -> {
                return true; // angenommen; die UI nutzt sie nicht, Forge ordnet Trigger selbst
            }
            case "CONCEDE" -> {
                leave(seat);
                return true;
            }
            case "MANA_AUTO_PAYMENT_ON" -> {
                seat.autoPayDefault = true;
                return true;
            }
            case "MANA_AUTO_PAYMENT_OFF" -> {
                seat.autoPayDefault = false;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Offenen Prioritaets-Prompt dieses Sitzes schliessen (F-Tasten passen ihn selbst). */
    private void closePriorityPromptOf(HumanSeat seat) {
        PromptDto p = openPrompt.get();
        if (p != null && promptSeat == seat && "SELECT".equals(p.kind) && "priority".equals(p.mode) && openPrompt.compareAndSet(p, null)) {
            send(seat, new Messages.PromptClosed(p.id));
            notifyOthers(seat, null);
        }
    }

    private void closePromptOf(HumanSeat seat) {
        PromptDto p = openPrompt.get();
        if (p != null && promptSeat == seat && openPrompt.compareAndSet(p, null)) {
            send(seat, new Messages.PromptClosed(p.id));
            notifyOthers(seat, null);
        }
    }

    public void setTempo(TempoSettings.Preset preset) {
        tempo.apply(preset);
        inbox.add(() -> game.AI_TIMEOUT = Math.max(1, tempo.thinkSecs()));
    }

    public void setAutoPayDefault(HumanSeat seat, boolean on) {
        seat.autoPayDefault = on;
    }

    /** Client-Knopf "Auto bezahlen" bei offenem Mana-Prompt. */
    public void autoPayNow(HumanSeat seat) {
        PromptDto p = openPrompt.get();
        PromptBridge.Frame f = promptFrame;
        if (p == null || promptSeat != seat || !"PLAY_MANA".equals(p.kind) || !openPrompt.compareAndSet(p, null)) {
            return;
        }
        send(seat, new Messages.PromptClosed(p.id));
        inbox.add(() -> {
            if (f instanceof PromptBridge.InputFrame inf && frames.peek() == f && !f.done()) {
                inf.autoPay();
                if (!f.done()) {
                    f.dirty = true;
                }
            }
        });
    }

    // ------------------------------------------------------------------ Park-Schleife (Spiel-Thread)

    /**
     * Haelt den Spiel-Thread in einer Frage fest, bis sie beantwortet ist: Prompt senden, Kommandos aus der inbox
     * ausfuehren (auch verschachtelte Fragen), bei aufgegebenem Sitz den Autopiloten antworten lassen.
     */
    /**
     * Spielende waehrend einer offenen Eingabe (Abbruch, Aufgabe, Zuglimit): rollt den Spiel-Thread aus Forge heraus.
     * Eine Eingabe kehrt nach Spielende sofort zurueck, und manche Aufrufer fragen dann endlos neu (z. B.
     * {@code TargetSelection.chooseTargets} ruft sich rekursiv auf, solange nicht abgebrochen wurde). Kein Fehler,
     * {@link #runGame} faengt es still ab.
     */
    static final class GameEnded extends Error {
        GameEnded() {
            super("Spielende waehrend einer Eingabe", null, false, false);
        }
    }

    void park(PromptBridge.Frame frame) {
        if (!isGameThread()) {
            throw new IllegalStateException("park() ausserhalb des Spiel-Threads: " + Thread.currentThread().getName());
        }
        if (game.isGameOver()) {
            throw new GameEnded();
        }
        frames.push(frame);
        parked = true;
        HumanSeat fs = frame.seat;
        if (fs.repeat != null && !(frame instanceof PromptBridge.InputFrame inf && (inf.stateMode() == PromptBridge.StateMode.MANA
                || inf.stateMode() == PromptBridge.StateMode.PRIORITY))) {
            fs.controller().stopRepeat("Mehrfach-Aktivierung angehalten – bitte hier selbst entscheiden.");
        }
        try {
            while (!frame.done()) {
                if (frame.seat.conceded()) {
                    frame.autopilot();
                    if (frame.done()) {
                        break;
                    }
                }
                if (frame.dirty) {
                    frame.dirty = false;
                    publish(frame);
                    continue;
                }
                Runnable cmd;
                try {
                    cmd = inbox.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (cmd != null) {
                    run(cmd);
                }
            }
        } finally {
            frames.pop();
            if (promptFrame == frame) {
                PromptDto p = openPrompt.get();
                if (p != null && openPrompt.compareAndSet(p, null)) {
                    send(frame.seat, new Messages.PromptClosed(p.id));
                    notifyOthers(frame.seat, null);
                }
                promptFrame = null;
            }
            PromptBridge.Frame outer = frames.peek();
            if (outer != null) {
                outer.dirty = true;
            }
            parked = outer != null;
        }
        if (game.isGameOver()) {
            throw new GameEnded();
        }
    }

    private void publish(PromptBridge.Frame frame) {
        PromptDto prompt = frame.build();
        if (prompt == null || frame.done()) {
            return;
        }
        // Forges Knopftexte ("Yes"/"No"/"Top" ...) auf Deutsch
        prompt.leftBtn = dev.magelite.view.ForgeText.button(prompt.leftBtn);
        prompt.rightBtn = dev.magelite.view.ForgeText.button(prompt.rightBtn);
        HumanSeat seat = frame.seat;
        PromptDto old = openPrompt.getAndSet(null);
        if (old != null && promptSeat != null) {
            send(promptSeat, new Messages.PromptClosed(old.id));
        }
        thinking = null;
        StateDto state = sendState(seat, frame.stateMode());
        prompt.playerId = seat.playerId;
        prompt.id = promptSeq.incrementAndGet();
        prompt.stateSeq = state == null ? 0 : state.seq;
        promptSeat = seat;
        promptFrame = frame;
        openPrompt.set(prompt);
        send(seat, prompt);
        notifyOthers(seat, seat.name());
    }

    private void run(Runnable cmd) {
        try {
            cmd.run();
        } catch (GameEnded e) {
            throw e;
        } catch (Throwable e) {
            LOG.error("Kommando fehlgeschlagen", e);
        }
    }

    /** Safe-Point vor jeder Entscheidung eines Controllers: inbox abarbeiten, Zuglimit, gedrosselter State. */
    void safePoint() {
        if (!isGameThread()) {
            return;
        }
        Runnable cmd;
        while ((cmd = inbox.poll()) != null) {
            run(cmd);
        }
        int cap = turnCap;
        if (cap > 0 && !game.isGameOver() && game.getPhaseHandler().getTurn() > cap) {
            LOG.info("Zuglimit " + cap + " erreicht - Unentschieden");
            game.setGameOver(GameEndReason.Draw);
        }
        if (aborting && !game.isGameOver()) {
            game.setGameOver(GameEndReason.Draw);
        }
        int t = game.getPhaseHandler().getTurn();
        if (t != decisionTurn) {
            decisionTurn = t;
            decisionsThisTurn = 0;
        }
        if (pendingPauseMs > 0) {
            int ms = pendingPauseMs;
            pendingPauseMs = 0;
            sendState(null, PromptBridge.StateMode.NONE); // erst zeigen, dann warten
            pause(ms);
        }
        if (++decisionsThisTurn > MAX_DECISIONS_PER_TURN && !game.isGameOver()) {
            LOG.warn("Endlosschleife vermutet: " + decisionsThisTurn + " Entscheidungen in Zug " + t + " - Unentschieden");
            addLog("INFO", "Endlosschleife erkannt – das Spiel endet unentschieden.");
            game.setGameOver(GameEndReason.Draw);
        }
        onUpdate();
    }

    /** Bot beginnt/beendet eine Entscheidung; "denkt" meldet der Wachhund erst nach 300 ms. */
    void botThinking(Player bot, boolean on) {
        if (on) {
            decidingSince = System.currentTimeMillis();
            deciding = bot;
        } else {
            deciding = null;
            if (thinking == bot) {
                thinking = null;
            }
        }
    }

    /** Pause vor der naechsten Entscheidung (Bot-Aktion sichtbar machen); Spiel-Thread. */
    void requestPause(int ms) {
        pendingPauseMs = Math.max(pendingPauseMs, ms);
    }

    /** Spiel-Thread wartet, arbeitet dabei Kommandos ab (Antworten, Aufgeben, Tempo). */
    private void pause(int ms) {
        long until = System.currentTimeMillis() + ms;
        long left;
        while ((left = until - System.currentTimeMillis()) > 0 && !aborting) {
            try {
                Runnable cmd = inbox.poll(left, TimeUnit.MILLISECONDS);
                if (cmd != null) {
                    run(cmd);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void notifyOthers(HumanSeat seat, String waitingFor) {
        Messages.Status st = new Messages.Status(null, false, waitingFor);
        if (humans.size() >= 2) {
            for (HumanSeat o : humans.values()) {
                if (o != seat) {
                    send(o, st);
                }
            }
        }
        sendSpectators(st);
    }

    void toast(HumanSeat seat, String level, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        send(seat, new Messages.Toast(level, RichText.parse(text)));
    }

    // ------------------------------------------------------------------ States

    void onUpdate() {
        if (!isGameThread()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStateAt >= STATE_MIN_INTERVAL_MS) {
            sendState(null, PromptBridge.StateMode.NONE);
        } else {
            stateDirty = true;
        }
    }

    private void flushStateIfDirty() {
        if (stateDirty && isGameThread()) {
            sendState(null, PromptBridge.StateMode.NONE);
        }
    }

    /**
     * Baut und sendet jedem Menschen seinen State; {@code forSeat} bekommt die spielbaren Objekte dazu.
     *
     * @return der State von {@code forSeat} (bzw. des ersten Menschen)
     */
    private StateDto sendState(HumanSeat forSeat, PromptBridge.StateMode mode) {
        flushFx(); // Ereignisse vor dem State, der sie widerspiegelt (Outbox haelt die Reihenfolge)
        trackEliminations();
        turn = game.getPhaseHandler().getTurn();
        long seq = stateSeq.incrementAndGet();
        StateDto mine = null;
        for (HumanSeat s : humans.values()) {
            ForgeViewMapper.Playable pl = null;
            if (s == forSeat) {
                if (mode == PromptBridge.StateMode.PRIORITY) {
                    Set<CardView> actions = s.pass.cachedActions != null ? s.pass.cachedActions
                            : ForgeViewMapper.actionable(s.player, AutoPassPolicy.budgetMs(s.player));
                    pl = mapper.priorityPlayable(s.player, actions);
                } else if (mode == PromptBridge.StateMode.MANA) {
                    pl = mapper.manaPlayable(s.player);
                }
                s.pass.cachedActions = null;
            }
            StateDto st = mapper.map(s.player, seq, pl, thinking, deckNames);
            addReveals(st, s.player);
            if (!s.replDeclineAlways.isEmpty()) {
                st.replDeclines = new ArrayList<>(new java.util.LinkedHashSet<>(s.replDeclineAlways.values()));
            }
            List<String> skips = AutoPassPolicy.skips(s.pass);
            if (skips != null) {
                for (var p : st.players) {
                    if (p.me) {
                        p.skips = skips;
                    }
                }
            }
            s.lastState = st;
            send(s, st);
            if (s == forSeat || (mine == null && forSeat == null)) {
                mine = st;
            }
        }
        lastStateAt = System.currentTimeMillis();
        stateDirty = false;
        if (spectatable) {
            sendPublicState(seq);
        }
        return mine;
    }

    private void sendPublicState(long seq) {
        try {
            if (viewpoint == null) {
                viewpoint = viewpointId();
            }
            HumanSeat vs = viewpoint == null ? null : humans.get(viewpoint);
            StateDto pub = mapper.mapPublic(vs == null ? null : vs.player, seq, thinking, deckNames);
            addReveals(pub, null);
            synchronized (specLock) {
                publicState = pub;
                sendSpectators(pub);
            }
        } catch (Throwable e) {
            long now = System.currentTimeMillis();
            if (now - lastPublicErrorAt > 30_000) {
                lastPublicErrorAt = now;
                LOG.error("Zuschau-Sicht fehlgeschlagen (Spieler laufen weiter)", e);
            }
        }
    }

    private void trackEliminations() {
        for (Player p : game.getRegisteredPlayers()) {
            if (p.hasLost() && !eliminatedTurn.containsKey(p.getId())) {
                eliminatedTurn.put(p.getId(), game.getPhaseHandler().getTurn());
                eliminationOrder.add(p.getId());
            }
        }
    }

    private void onLogAdded() {
        List<GameLogEntry> all = game.getGameLog().getAllEntries();
        if (all.isEmpty()) {
            return;
        }
        GameLogEntry e = all.get(all.size() - 1);
        if (logNoise(e)) {
            return;
        }
        addLog(e.type() == null ? "INFO" : e.type().name(), dev.magelite.view.ForgeText.clean(e.message()));
    }

    /**
     * Forge-Eintraege ohne Wert fuer den Spielverlauf (XMage hatte sie nicht): jeder Phasenschritt (gut 60 % aller
     * Zeilen), Mana-Faehigkeiten, Match-Statistik, "restored control over themself" zu Spielbeginn, Dauer/Zugzahl am Ende.
     */
    static boolean logNoise(GameLogEntry e) {
        if (e.type() == null) {
            return false;
        }
        String m = e.message() == null ? "" : e.message().trim();
        return switch (e.type()) {
            case PHASE, MANA, MATCH_RESULTS -> true;
            case PLAYER_CONTROL -> m.endsWith("restored control over themself");
            case GAME_OUTCOME -> m.startsWith("Match Duration") || m.matches("Turn \\d+");
            default -> false;
        };
    }

    private void addLog(String kind, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Player active = game.getPhaseHandler().getPlayerTurn();
        Messages.LogEntry entry = new Messages.LogEntry(System.currentTimeMillis(), game.getPhaseHandler().getTurn(),
                active == null ? null : active.getName(), kind, RichText.parse(text));
        synchronized (logTail) {
            logTail.addLast(entry);
            while (logTail.size() > LOG_KEEP) {
                logTail.removeFirst();
            }
        }
        emitPublic(new Messages.Log(List.of(entry)));
    }

    private void emit(Object msg) {
        for (HumanSeat s : humans.values()) {
            send(s, msg);
        }
    }

    private void send(HumanSeat seat, Object msg) {
        if (msg instanceof StateDto || msg instanceof PromptDto || msg instanceof Messages.Log) {
            lastProgressAt = System.currentTimeMillis();
        }
        try {
            seat.sink.send(msg);
        } catch (Throwable e) {
            LOG.warn("Senden fehlgeschlagen: " + e);
        }
    }

    // ------------------------------------------------------------------ Aufgedeckte / angesehene Karten

    /**
     * Ein Aufdecken: Karten (je Empfaenger als DTO aus dessen Sicht, waehrend Forge sie ihm zeigt), Empfaenger und
     * Zeitpunkt. Oeffentlich, wenn alle Spieler im Spiel (ggf. ohne den Besitzer) es gesehen haben.
     */
    private static final class Reveal {
        final Set<Integer> cardIds;
        final String name;
        final Set<Player> recipients = new HashSet<>();
        final Map<Player, List<dev.magelite.view.dto.CardDto>> dtos = new LinkedHashMap<>();
        final Player owner;
        final int turn;
        final forge.game.phase.PhaseType phase;
        List<dev.magelite.view.dto.CardDto> publicDtos;

        Reveal(Set<Integer> cardIds, String name, Player owner, int turn, forge.game.phase.PhaseType phase) {
            this.cardIds = cardIds;
            this.name = name;
            this.owner = owner;
            this.turn = turn;
            this.phase = phase;
        }
    }

    /** Spiel-Thread: ein Spieler bekommt Karten gezeigt ({@code SeatGui.reveal} bzw. {@code ForgeBot.reveal}). */
    void noteReveal(Player recipient, String message, java.util.Collection<forge.game.card.Card> cards, Player owner) {
        if (cards == null || cards.isEmpty() || recipient == null) {
            return;
        }
        Set<Integer> idSet = new HashSet<>();
        for (forge.game.card.Card c : cards) {
            idSet.add(c.getId());
        }
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        Reveal r = null;
        for (int i = reveals.size() - 1; i >= 0; i--) {
            Reveal x = reveals.get(i);
            if (x.cardIds.equals(idSet) && x.turn == ph.getTurn() && x.phase == ph.getPhase() && !x.recipients.contains(recipient)) {
                r = x;
                break;
            }
        }
        if (r == null) {
            String name = message == null || message.isBlank() ? "Aufgedeckt"
                    : dev.magelite.view.ForgeText.german(message.replace('\n', ' ').trim());
            r = new Reveal(idSet, name.length() > 80 ? name.substring(0, 79) + "…" : name, owner, ph.getTurn(), ph.getPhase());
            reveals.add(r);
            while (reveals.size() > 20) {
                reveals.remove(0);
            }
        }
        r.recipients.add(recipient);
        if (humans.containsKey(ids.player(recipient.getId()))) {
            List<dev.magelite.view.dto.CardDto> dtos = new ArrayList<>();
            for (forge.game.card.Card c : cards) {
                dtos.add(mapper.card(c, recipient.getView()));
            }
            r.dtos.put(recipient, dtos);
            if (r.publicDtos == null) {
                r.publicDtos = dtos;
            }
        }
    }

    /** Aufgedeckte Karten in den State: oeffentlich -> revealed, nur fuer mich -> lookedAt. Alte verfallen. */
    private void addReveals(StateDto st, Player viewer) {
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        reveals.removeIf(r -> (r.turn != ph.getTurn() || r.phase != ph.getPhase()) && game.getStack().isEmpty());
        if (reveals.isEmpty()) {
            return;
        }
        List<dev.magelite.view.dto.NamedCardsDto> pub = new ArrayList<>();
        List<dev.magelite.view.dto.NamedCardsDto> mine = new ArrayList<>();
        for (Reveal r : reveals) {
            boolean everyone = true;
            for (Player p : game.getPlayers()) {
                if (p != r.owner && !r.recipients.contains(p)) {
                    everyone = false;
                    break;
                }
            }
            if (everyone && r.publicDtos != null) {
                List<dev.magelite.view.dto.CardDto> d = viewer != null && r.dtos.containsKey(viewer) ? r.dtos.get(viewer) : r.publicDtos;
                pub.add(new dev.magelite.view.dto.NamedCardsDto(r.name, d));
            } else if (viewer != null && r.dtos.containsKey(viewer)) {
                mine.add(new dev.magelite.view.dto.NamedCardsDto(r.name, r.dtos.get(viewer)));
            }
        }
        st.revealed = pub.isEmpty() ? null : pub;
        if (viewer != null) {
            st.lookedAt = mine.isEmpty() ? null : mine;
        }
    }

    // ------------------------------------------------------------------ Ereignisse (Animationen)

    /** Spiel-Thread ({@link ForgeEvents}): Ereignis einreihen. */
    void onFx(Messages.FxEvent e) {
        if (fx.isEmpty()) {
            lastFxAt = System.currentTimeMillis();
        }
        fx.add(e);
        if (fxSize.incrementAndGet() > FX_MAX && fx.poll() != null) {
            fxSize.decrementAndGet();
        }
    }

    /**
     * Gesammelte Ereignisse an alle Menschen: gleiche Token-Tode zu einem Eintrag (xN), Lebensverlust durch Schaden
     * nicht doppelt, verdeckte Karten nur an den Besitzer. Spiel-Thread (vor jedem State) oder Wachhund.
     */
    private void flushFx() {
        if (fx.isEmpty()) {
            return;
        }
        List<Messages.FxEvent> batch = new ArrayList<>();
        Messages.FxEvent e;
        while ((e = fx.poll()) != null) {
            fxSize.decrementAndGet();
            batch.add(e);
        }
        Map<String, Integer> tokenDeaths = new LinkedHashMap<>();
        List<Messages.FxEvent> out = new ArrayList<>();
        for (Messages.FxEvent x : batch) {
            if ("tokenDied".equals(x.kind()) && x.name() != null) {
                tokenDeaths.merge(x.name() + "|" + x.ownerId(), 1, Integer::sum);
            }
        }
        Set<String> tokenSeen = new HashSet<>();
        for (Messages.FxEvent x : batch) {
            if ("tokenDied".equals(x.kind()) && x.name() != null) {
                String key = x.name() + "|" + x.ownerId();
                if (!tokenSeen.add(key)) {
                    continue;
                }
                int n = tokenDeaths.getOrDefault(key, 1);
                out.add(n > 1 ? x.withAmount(n) : x);
                continue;
            }
            if ("life".equals(x.kind()) && x.amount() != null && x.amount() < 0) {
                int lost = -x.amount();
                boolean fromDamage = batch.stream().anyMatch(d -> "damage".equals(d.kind()) && d.objectId() == null
                        && Objects.equals(d.playerId(), x.playerId()) && d.amount() != null && d.amount() == lost);
                if (fromDamage) {
                    continue;
                }
            }
            out.add(x);
        }
        for (HumanSeat s : humans.values()) {
            List<Messages.FxEvent> mine = new ArrayList<>(out.size());
            for (Messages.FxEvent x : out) {
                if (Boolean.TRUE.equals(x.hidden()) && !s.playerId.equals(x.ownerId())) {
                    continue;
                }
                mine.add(x);
            }
            if (!mine.isEmpty()) {
                send(s, new Messages.Events(mine));
            }
        }
        if (!spectators.isEmpty()) {
            List<Messages.FxEvent> pub = out.stream().filter(x -> !Boolean.TRUE.equals(x.hidden())).toList();
            if (!pub.isEmpty()) {
                sendSpectators(new Messages.Events(pub));
            }
        }
    }

    // ------------------------------------------------------------------ Wachhund (Aktivitaet)

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private void watchdogTick() {
        try {
            Thread t = gameThread;
            if (t == null || !t.isAlive()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (ticks % 4 == 0 && humans.size() > 1
                    && humans.values().stream().anyMatch(s -> !s.conceded && !s.connected())) {
                broadcastSeats();
            }
            Player d = deciding;
            if (d != null && thinking != d && now - decidingSince > 300) {
                thinking = d;
                emitPublic(new Messages.Status(ids.player(d.getId()), false, d.getName()));
            }
            if (!fx.isEmpty() && now - lastFxAt > FX_FLUSH_AFTER_MS) {
                flushFx(); // kein State in Sicht (gedrosselt/wartend): Ereignisse trotzdem zeigen
            }
            if (++ticks % 2 == 0) {
                sendActivity(t, now);
            }
        } catch (Throwable e) {
            LOG.warn("Wachhund: " + e);
        }
    }

    private void sendActivity(Thread t, long now) {
        int cpu = cpuPercent(now);
        long idle = now - lastProgressAt;
        Player th = thinking;
        HumanSeat owner = parked && openPrompt.get() != null ? promptSeat : null;
        String mode;
        String who = null;
        if (owner != null) {
            mode = "you";
        } else if (th != null) {
            mode = "bot";
            who = th.getName();
        } else if (t.getState() == Thread.State.RUNNABLE || cpu >= 15) {
            mode = "engine";
        } else {
            mode = "idle";
        }
        if (!"you".equals(mode) && idle > STUCK_AFTER_MS && cpu >= 0 && cpu < 5) {
            mode = "stuck";
        }
        for (HumanSeat s : humans.values()) {
            if (owner != null && s != owner) {
                send(s, new Messages.Activity("human", owner.name(), cpu, idle, 0));
            } else {
                send(s, new Messages.Activity(mode, who, cpu, idle, 0));
            }
        }
        if (!spectators.isEmpty()) {
            sendSpectators(owner != null ? new Messages.Activity("human", owner.name(), cpu, idle, 0)
                    : new Messages.Activity(mode, who, cpu, idle, 0));
        }
    }

    private int cpuPercent(long now) {
        if (!THREADS.isThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) {
            return -1;
        }
        long sum = 0;
        for (long tid : THREADS.getAllThreadIds()) {
            long c = THREADS.getThreadCpuTime(tid);
            if (c > 0) {
                sum += c;
            }
        }
        int pct = 0;
        if (lastCpuNs >= 0 && now > lastCpuAt) {
            pct = (int) Math.max(0, (sum - lastCpuNs) / 1e4 / (now - lastCpuAt));
        }
        lastCpuNs = sum;
        lastCpuAt = now;
        return pct;
    }

    // ------------------------------------------------------------------ Spielende

    private Messages.GameOver buildGameOver(String error) {
        List<Player> all = new ArrayList<>(game.getRegisteredPlayers());
        Map<Integer, Integer> place = new LinkedHashMap<>();
        Player winner = null;
        // Forge markiert bei Unentschieden (Zuglimit, Abbruch) alle Uebrigen als "gewonnen"
        boolean draw = game.getOutcome() == null || game.getOutcome().getWinCondition() == GameEndReason.Draw
                || all.stream().filter(Player::hasWon).count() != 1;
        for (Player p : all) {
            if (!draw && p.hasWon()) {
                winner = p;
                place.put(p.getId(), 1);
            }
        }
        int nextPlace = winner == null ? 1 : 2;
        for (boolean leavers : new boolean[] {false, true}) {
            int group = 0;
            for (Player p : all) {
                HumanSeat h = humans.get(ids.player(p.getId()));
                boolean left = h != null && h.left;
                if (left == leavers && !place.containsKey(p.getId()) && !eliminatedTurn.containsKey(p.getId())) {
                    place.put(p.getId(), nextPlace);
                    group++;
                }
            }
            nextPlace += group;
        }
        for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
            int pid = eliminationOrder.get(i);
            if (!place.containsKey(pid)) {
                place.put(pid, nextPlace++);
            }
        }
        List<Messages.Placement> placements = new ArrayList<>();
        for (Player p : all) {
            UUID wire = ids.player(p.getId());
            HumanSeat h = humans.get(wire);
            placements.add(new Messages.Placement(wire, p.getName(), place.getOrDefault(p.getId(), all.size()),
                    h != null, p.getLife(), eliminatedTurn.get(p.getId()), h == null ? 0 : h.mulligans));
        }
        placements.sort((a, b) -> Integer.compare(a.place(), b.place()));
        String result = winner == null ? "Unentschieden" : winner.getName() + " gewinnt";
        return new Messages.GameOver(winner == null ? null : ids.player(winner.getId()), result, placements,
                game.getPhaseHandler().getTurn(), System.currentTimeMillis() - startedAt, null, error);
    }

    public int currentTurn() {
        return turn;
    }

    public long startedAt() {
        return startedAt;
    }

    public List<HumanSeat> humanSeats() {
        return List.copyOf(humans.values());
    }

    /** null bei reinen Bot-Spielen. */
    public GameSetup getSetup() {
        return setup;
    }

    public Map<UUID, String> getDeckNames() {
        return new LinkedHashMap<>(deckNames);
    }

    public Map<UUID, List<String>> getCommanders() {
        return new LinkedHashMap<>(commanders);
    }

    public void setRewardHook(RewardHook hook) {
        this.rewardHook = hook;
    }

    public Map<UUID, Integer> getEliminatedTurns() {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        eliminatedTurn.forEach((pid, t) -> out.put(ids.player(pid), t));
        return out;
    }

    /** Wire-id einer Forge-Karte. */
    UUID wireCard(forge.game.card.Card c) {
        return ids.card(c.getId());
    }

    /** Wire-id einer Forge-Karte (Tests, Zuschauer-Pruefung). */
    public UUID wireCardId(int forgeCardId) {
        return ids.card(forgeCardId);
    }

    /** Diagnose fuer Spikes: nicht abgebildete bzw. automatisch beantwortete Forge-Fragen. */
    public Map<String, Integer> unmappedCounts() {
        return bridge.unmappedCounts();
    }

    public Map<String, Integer> autoCounts() {
        return bridge.autoCounts();
    }

    public void shutdownNow() {
        abort();
        try {
            if (!awaitEnd(10_000)) {
                LOG.warn("Spiel-Thread endet nicht");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
