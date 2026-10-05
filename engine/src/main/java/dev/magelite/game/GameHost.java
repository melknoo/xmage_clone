package dev.magelite.game;

import dev.magelite.deck.LoadedDeck;
import dev.magelite.stats.StatsSink;
import dev.magelite.stats.StatsWatcher;
import dev.magelite.view.GameViewMapper;
import dev.magelite.view.RichText;
import dev.magelite.view.dto.Messages;
import dev.magelite.view.dto.PromptDto;
import dev.magelite.view.dto.StateDto;
import mage.constants.ManaType;
import mage.constants.PlayerAction;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.GameOptions;
import mage.game.events.PlayerQueryEvent;
import mage.game.events.TableEvent;
import mage.player.human.HumanPlayer;
import mage.players.Player;
import mage.util.ThreadUtils;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Fuehrt ein Spiel (1-4 Menschen, Rest Bots) ohne XMage-Server aus. Ersetzt GameController/GameSessionPlayer.
 * <p>
 * Threads:
 * <ul>
 *   <li>Game-Thread ({@code GAME <id>}): {@code game.start()}, alle Listener, GameView-Bau.</li>
 *   <li>CALL-Thread: einziger Aufrufer von {@code setResponse*}/{@code sendPlayerAction}. Antworten
 *       vom Game-Thread wuerden 30 s blockieren und verworfen.</li>
 * </ul>
 * Jeder Mensch hat einen {@link HumanSeat} (Verbindung, eigener State, Auto-Pay-Zustand). Da das Spiel single-threaded
 * ist, gibt es immer hoechstens einen offenen Prompt; er gehoert genau einem Sitz ({@link #promptSeat}). Antworten
 * werden nur vom Besitzer angenommen.
 */
public final class GameHost {

    private static final Logger LOG = Logger.getLogger(GameHost.class);
    private static final long STATE_MIN_INTERVAL_MS = 60;
    private static final int LOG_KEEP = 400;
    /** Max. Wartezeit, bis der Spiel-Thread wirklich auf eine Antwort wartet (sonst wie bisher antworten). */
    private static final long AWAIT_WAITING_MS = 3000;
    /** Spiel haengt nach einer Antwort weiter in waitForResponse, ohne neue Frage -> Antwort erneut zustellen. */
    private static final long RECOVER_AFTER_MS = 2500;
    private static final int RECOVER_MAX_PER_ANSWER = 3;
    /** Ohne Spielaenderung und ohne CPU-Last so lange -> "stuck" melden. */
    private static final long STUCK_AFTER_MS = 15000;
    /** Ab so langer Trennung duerfen die anderen Menschen einen Sitz aufgeben lassen (Dev: -Dmagelite.kickAfterMs). */
    private static final long KICK_AFTER_MS = Long.getLong("magelite.kickAfterMs", 60_000L);

    /** F-Tasten und Einstellungen, die der Client senden darf (kein Undo/Rollback/Cheat). */
    private static final Set<PlayerAction> ALLOWED_ACTIONS = EnumSet.of(
            PlayerAction.PASS_PRIORITY_UNTIL_MY_NEXT_TURN,
            PlayerAction.PASS_PRIORITY_UNTIL_TURN_END_STEP,
            PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN,
            PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK,
            PlayerAction.PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE,
            PlayerAction.PASS_PRIORITY_UNTIL_STACK_RESOLVED,
            PlayerAction.PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN,
            PlayerAction.PASS_PRIORITY_CANCEL_ALL_ACTIONS,
            PlayerAction.HOLD_PRIORITY,
            PlayerAction.UNHOLD_PRIORITY,
            PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_FIRST,
            PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_LAST,
            PlayerAction.TRIGGER_AUTO_ORDER_NAME_FIRST,
            PlayerAction.TRIGGER_AUTO_ORDER_NAME_LAST,
            PlayerAction.TRIGGER_AUTO_ORDER_RESET_ALL,
            PlayerAction.REQUEST_AUTO_ANSWER_ID_YES,
            PlayerAction.REQUEST_AUTO_ANSWER_ID_NO,
            PlayerAction.REQUEST_AUTO_ANSWER_TEXT_YES,
            PlayerAction.REQUEST_AUTO_ANSWER_TEXT_NO,
            PlayerAction.REQUEST_AUTO_ANSWER_RESET_ALL,
            PlayerAction.RESET_AUTO_SELECT_REPLACEMENT_EFFECTS,
            PlayerAction.MANA_AUTO_PAYMENT_ON,
            PlayerAction.MANA_AUTO_PAYMENT_OFF,
            PlayerAction.MANA_AUTO_PAYMENT_RESTRICTED_ON,
            PlayerAction.MANA_AUTO_PAYMENT_RESTRICTED_OFF,
            PlayerAction.USE_FIRST_MANA_ABILITY_ON,
            PlayerAction.USE_FIRST_MANA_ABILITY_OFF,
            PlayerAction.CONCEDE
    );

    /** Empfaenger fuer Server->Client-Nachrichten (WebSocket, Konsole, Test-Treiber). */
    public interface Sink {
        void send(Object message);
    }

    /** Belohnung pro menschlichem Sitz am Spielende (z.B. XP); null = keine. */
    public interface RewardHook {
        Object apply(GameHost host, HumanSeat seat, Messages.GameOver over);
    }

    /** Antwort des Clients auf einen Prompt. Genau ein Feld gesetzt. */
    public record Response(UUID uuid, Boolean bool, Integer integer, String string, UUID manaPlayerId, ManaType manaType) {
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

        public static Response ofMana(UUID playerId, ManaType type) {
            return new Response(null, null, null, null, playerId, type);
        }
    }

    private static final Sink NOOP = msg -> {
    };

    /**
     * Ein menschlicher Sitz: XMage-Spieler, Konto, Verbindung und alles, was pro Mensch gilt (letzter State,
     * Auto-Passen, Auto-Bezahlen, gepasste Stapelobjekte, Mehrfach-Angriff, Aufgabe).
     */
    public final class HumanSeat {
        private final HumanPlayer player;
        private final UUID playerId;
        private final long userId;
        private final Long deckId;
        private final LoadedDeck deck;
        private final boolean host;
        private volatile Sink sink = NOOP;
        /** 0 = Client verbunden; sonst Zeitpunkt der Trennung (bzw. Erzeugung, solange sich noch niemand verbunden hat) */
        private volatile long disconnectedSince = System.currentTimeMillis();
        private volatile StateDto lastState;
        private volatile Messages.GameOver gameOver;
        private volatile boolean autoPass = true;
        private volatile boolean autoPayDefault = true;
        private volatile boolean conceded;
        /** Stapelobjekte, auf die dieser Mensch schon gepasst hat ({@link StackSig}); gleiche danach automatisch passen. */
        private final Set<String> passedSigs = ConcurrentHashMap.newKeySet();
        /** Signatur des obersten Stapelobjekts zum offenen Prioritaets-Prompt {@link #promptSigId} */
        private volatile String promptSig;
        private volatile long promptSigId;
        /** laufender Mehrfach-Angriff/-Block (Game-Thread, gestartet vom WS-Thread) */
        private volatile CombatMacro macro;
        /** laufende Ersatzeffekt-Entscheidung (1-Klick / "Keinen anwenden"), siehe {@link #handleReplacement} */
        private volatile ReplMacro replMacro;
        /** Ersatzeffekte (Regeltext -> Kurzname), die dieses Spiel automatisch abgelehnt werden */
        private final Map<String, String> replDeclineAlways = new ConcurrentHashMap<>();
        // Auto-Bezahlen (Game-Thread)
        private boolean autoPayActive;
        private boolean autoPayFailed;
        private AutoPayer.Color autoPayColor;
        private int autoPaySteps;
        private String autoPayLastMsg;

        private HumanSeat(HumanPlayer player, GameSetup.SeatSpec spec, boolean host) {
            this.player = player;
            this.playerId = player.getId();
            this.userId = spec.userId();
            this.deckId = spec.deckId();
            this.deck = spec.deck();
            this.host = host;
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
            return player.getName();
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

    private final UUID id = UUID.randomUUID();
    private final MageLiteMatch match;
    private final Game game;
    /** menschliche Sitze nach Spieler-id, in Tischreihenfolge; nach dem Konstruktor nur gelesen */
    private final Map<UUID, HumanSeat> humans = new LinkedHashMap<>();
    private final HumanSeat firstHuman;
    private final List<MageLiteBot> bots = new ArrayList<>();
    private final TempoSettings tempo;
    private final Map<UUID, String> deckNames = new LinkedHashMap<>();
    private final List<Messages.Seat> seats = new ArrayList<>();
    private final Map<UUID, List<String>> commanders = new LinkedHashMap<>();
    private final GameSetup setup;
    private final ExecutorService callExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, ThreadUtils.THREAD_PREFIX_CALL_REQUEST + " magelite");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "magelite-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong stateSeq = new AtomicLong();
    private final AtomicLong promptSeq = new AtomicLong();
    private final Deque<Messages.LogEntry> logTail = new ArrayDeque<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, Integer> eliminatedTurn = new LinkedHashMap<>();

    /** offener Prompt; Antworten raeumen ihn per compareAndSet (genau eine Antwort pro Prompt) */
    private final AtomicReference<PromptDto> openPrompt = new AtomicReference<>();
    /** Besitzer von {@link #openPrompt} */
    private volatile HumanSeat promptSeat;
    private volatile UUID thinking;
    private volatile Messages.GameOver gameOver;
    private volatile Consumer<GameHost> onFinished;
    private volatile RewardHook rewardHook;
    private long lastStateAt;
    private boolean stateDirty;
    private volatile Thread gameThread;
    private long startedAt;
    // Wachhund / Aktivitaet
    private volatile long lastProgressAt = System.currentTimeMillis();
    private volatile long lastHumanQueryAt;
    private volatile Player lastAppliedTarget;
    private volatile HumanSeat lastAppliedSeat;
    private volatile Response lastApplied;
    private volatile long lastAppliedAt;
    private int recoverAttempts;
    private volatile int recovered;
    private long lastCpuNs = -1;
    private long lastCpuAt;
    private int ticks;

    private GameHost(GameSetup setup) throws GameException {
        this.setup = setup;
        this.tempo = new TempoSettings(setup.tempo());
        this.match = new MageLiteMatch(MageLiteMatch.defaultOptions("MageLite"));

        Set<String> usedNames = new HashSet<>();
        HumanSeat first = null;
        for (GameSetup.SeatSpec spec : setup.seats()) {
            if (spec.human()) {
                String name = uniqueName(spec.name() == null || spec.name().isBlank() ? "Spieler" : spec.name(), usedNames);
                HumanPlayer hp = new HumanPlayer(name, RangeOfInfluence.ALL, 0);
                hp.setUserData(HumanSettings.defaults());
                HumanSeat seat = new HumanSeat(hp, spec, first == null);
                humans.put(hp.getId(), seat);
                if (first == null) {
                    first = seat;
                }
                addSeat(hp, spec.deck(), true);
            } else {
                String name = botName(spec.deck(), usedNames);
                MageLiteBot bot = new MageLiteBot(name, RangeOfInfluence.ALL, tempo);
                bot.setHooks((g, b) -> flushStateIfDirty());
                bots.add(bot);
                addSeat(bot, spec.deck(), false);
            }
        }
        firstHuman = first;

        match.startMatch();
        match.startGame();
        game = match.getGame();
        GameOptions options = GameOptions.getDefault().copy();
        options.rollbackTurnsAllowed = false;
        game.setGameOptions(options);
        game.addTableEventListener(this::onTableEvent);
        game.addPlayerQueryEventListener(this::onQueryEvent);
        for (HumanSeat s : humans.values()) {
            StatsSink.register(game.getId(), s.playerId);
        }
        game.getState().addWatcher(new StatsWatcher());
    }

    public static GameHost create(GameSetup setup) throws GameException {
        return new GameHost(setup);
    }

    private void addSeat(Player player, LoadedDeck deck, boolean isHuman) throws GameException {
        match.addPlayer(player, deck.newDeck());
        deckNames.put(player.getId(), deck.name());
        commanders.put(player.getId(), deck.commanders());
        seats.add(new Messages.Seat(player.getId(), player.getName(), isHuman, deck.name(), deck.commanders()));
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

    // ------------------------------------------------------------------ Lebenszyklus

    public UUID getId() {
        return id;
    }

    /** Spieler-id des ersten Menschen (Gastgeber); fuer Szenarien und Tests. */
    public UUID getHumanId() {
        return firstHuman.playerId;
    }

    /** Menschliche Sitze in Tischreihenfolge. */
    public Collection<HumanSeat> seats() {
        return Collections.unmodifiableCollection(humans.values());
    }

    public HumanSeat firstSeat() {
        return firstHuman;
    }

    /** Sitz eines Kontos (lokal: Nutzer 1). */
    public Optional<HumanSeat> seatOf(long userId) {
        return humans.values().stream().filter(s -> s.userId == userId).findFirst();
    }

    public Optional<HumanSeat> seatOfPlayer(UUID playerId) {
        return Optional.ofNullable(humans.get(playerId));
    }

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

    public void setAutoPass(HumanSeat seat, boolean autoPass) {
        seat.autoPass = autoPass;
    }

    /**
     * Verbindet einen Client mit einem Sitz und schickt den aktuellen Stand (Resync nach Reconnect).
     */
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
        if (humans.size() > 1) {
            broadcastSeats();
        }
    }

    public void detach(HumanSeat seat, Sink oldSink) {
        if (seat.sink == oldSink) {
            seat.sink = NOOP;
            seat.disconnectedSince = System.currentTimeMillis();
            if (humans.size() > 1) {
                broadcastSeats();
            }
        }
    }

    /** Verbindungszustand aller Menschen an alle Menschen (nur bei mehreren Menschen sinnvoll). */
    private void broadcastSeats() {
        List<Messages.SeatConn> list = new ArrayList<>();
        for (HumanSeat s : humans.values()) {
            list.add(new Messages.SeatConn(s.playerId, s.connected(), s.disconnectedForMs(), s.conceded));
        }
        emit(new Messages.SeatsStatus(list, KICK_AFTER_MS));
    }

    /**
     * Ein verbundener Mensch laesst einen seit mindestens {@link #KICK_AFTER_MS} getrennten Mitspieler aufgeben,
     * damit das Spiel nicht ewig auf dessen Prompt wartet.
     *
     * @return true, wenn der Sitz aufgegeben hat
     */
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

    /**
     * Seit wann kein (noch mitspielender) Mensch verbunden ist, in ms; 0, sobald einer verbunden ist.
     * Fuer den Abbruch verwaister Spiele.
     */
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
        gameThread = new Thread(this::runGame, ThreadUtils.THREAD_PREFIX_GAME + " " + game.getId());
        gameThread.start();
        watchdog.scheduleWithFixedDelay(this::watchdogTick, 500, 500, TimeUnit.MILLISECONDS);
    }

    private void runGame() {
        String error = null;
        try {
            game.start(null);
            game.fireUpdatePlayersEvent();
        } catch (Throwable e) {
            LOG.error("Spiel abgebrochen", e);
            error = e.toString();
        }
        try {
            trackEliminations();
            sendState(null, null);
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
            try {
                game.cleanUp();
                match.cleanUp();
            } catch (Throwable e) {
                LOG.warn("cleanUp: " + e);
            }
            callExecutor.shutdown();
            watchdog.shutdownNow();
        }
    }

    /**
     * Beendet das Spiel: alle Spieler geben auf (Interrupts schluckt XMage).
     */
    public void abort() {
        for (HumanSeat s : humans.values()) {
            s.conceded = true;
        }
        try {
            callExecutor.execute(() -> {
                for (Player p : game.getPlayers().values()) {
                    if (p.isInGame()) {
                        game.setConcedingPlayer(p.getId());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Spiel ist bereits zu Ende, CALL-Executor heruntergefahren
        }
    }

    /**
     * Ein Mensch verlaesst das Spiel: nur sein Sitz gibt auf. Sind danach keine Menschen mehr im Spiel, geben auch
     * die Bots auf (kein reines Bot-Spiel auf dem Server).
     */
    public void leave(HumanSeat seat) {
        if (seat.conceded) {
            return;
        }
        seat.conceded = true;
        closePromptOf(seat);
        send(seat, new Messages.SeatStatus(true));
        boolean humansLeft = humans.values().stream().anyMatch(s -> !s.conceded);
        try {
            callExecutor.execute(() -> {
                try {
                    if (seat.player.isInGame()) {
                        game.informPlayers(seat.player.getLogName() + " gibt auf");
                        game.setConcedingPlayer(seat.playerId);
                    }
                } catch (Throwable e) {
                    LOG.error("Aufgeben fehlgeschlagen", e);
                }
            });
        } catch (RejectedExecutionException e) {
            return;
        }
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

    /**
     * Antwort auf den offenen Prompt. Nur vom Besitzer des Prompts; ignoriert veraltete promptIds (Doppelklicks).
     */
    public boolean respond(HumanSeat seat, long promptId, Response r) {
        PromptDto p = openPrompt.get();
        if (p == null || p.id != promptId || promptSeat != seat || !openPrompt.compareAndSet(p, null)) {
            return false;
        }
        seat.macro = null;
        seat.replMacro = null;
        String sig = seat.promptSig;
        if (sig != null && seat.promptSigId == promptId && Boolean.FALSE.equals(r.bool())) {
            // gepasst -> gleiche Stapelobjekte laufen ab jetzt automatisch durch
            seat.passedSigs.add(sig);
        }
        send(seat, new Messages.PromptClosed(promptId));
        notifyOthers(seat, null);
        dispatch(seat, target -> apply(seat, target, r));
        return true;
    }

    /**
     * Mehrfach-Angriff/-Block: {@code ids} greifen {@code target} an (Angriffs-Prompt) bzw. blocken den Angreifer
     * {@code target} (Block-Prompt). Die Folge-Prompts beantwortet {@link #continueMacro}.
     */
    public boolean combat(HumanSeat seat, List<UUID> ids, UUID target) {
        PromptDto p = openPrompt.get();
        if (p == null || promptSeat != seat) {
            return false;
        }
        boolean attack = "attackers".equals(p.mode);
        if (!"SELECT".equals(p.kind) || target == null || ids == null || (!attack && !"blockers".equals(p.mode))) {
            send(seat, p); // Client hat den Prompt schon als beantwortet markiert -> erneut zustellen
            return false;
        }
        List<UUID> possible = attack ? p.possibleAttackers : p.possibleBlockers;
        Set<UUID> busy = inCombat(seat.lastState, attack);
        Deque<UUID> queue = new ArrayDeque<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            if (possible != null && possible.contains(id) && !busy.contains(id)) {
                queue.add(id);
            }
        }
        UUID first = queue.poll();
        if (first == null || !openPrompt.compareAndSet(p, null)) {
            // Client hat den Prompt schon als beantwortet markiert -> erneut zustellen, sonst steht die UI
            if (openPrompt.get() == p) {
                send(seat, p);
            }
            return false;
        }
        CombatMacro m = new CombatMacro(p.mode, queue, target);
        m.current = first;
        seat.macro = m;
        send(seat, new Messages.PromptClosed(p.id));
        notifyOthers(seat, null);
        dispatch(seat, t -> apply(seat, t, Response.ofUuid(first)));
        return true;
    }

    /**
     * Ersatzeffekt-Wahl mit Gruppen: {@code accept} = Effekt {@code key} anwenden, seine Folge-Frage ("Dredge X?")
     * beantwortet {@link #handleReplacement} mit Ja. {@code decline} = alle optionalen Effekte ablehnen (Wahl und
     * Nein-Kette automatisch), mit {@code always} auch in allen weiteren Ereignissen dieses Spiels.
     */
    public boolean replacement(HumanSeat seat, String mode, String key, boolean always) {
        PromptDto p = openPrompt.get();
        if (p == null || promptSeat != seat) {
            return false;
        }
        List<PromptDto.ReplGroup> groups = "CHOOSE_CHOICE".equals(p.kind) && p.choice != null ? p.choice.groups : null;
        ReplMacro m = null;
        String answer = null;
        if (groups != null && "accept".equals(mode) && key != null) {
            for (PromptDto.ReplGroup g : groups) {
                for (PromptDto.ReplSource s : g.sources()) {
                    if (key.equals(s.key())) {
                        m = new ReplMacro(true);
                        if (g.optional() && s.objectId() != null) {
                            m.sourceIds.add(s.objectId());
                        }
                        answer = key;
                    }
                }
            }
        } else if (groups != null && "decline".equals(mode)) {
            m = new ReplMacro(false);
            for (PromptDto.ReplGroup g : groups) {
                if (!g.optional()) {
                    continue;
                }
                m.add(g);
                if (always) {
                    seat.replDeclineAlways.put(g.rule(), g.label());
                }
                if (answer == null) {
                    answer = g.sources().get(0).key();
                }
            }
        }
        if (answer == null || !openPrompt.compareAndSet(p, null)) {
            // Client hat den Prompt schon als beantwortet markiert -> erneut zustellen, sonst steht die UI
            if (openPrompt.get() == p) {
                send(seat, p);
            }
            return false;
        }
        seat.macro = null;
        seat.replMacro = m;
        String a = answer;
        send(seat, new Messages.PromptClosed(p.id));
        notifyOthers(seat, null);
        dispatch(seat, t -> apply(seat, t, Response.ofString(a)));
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

    /** Laufende Ersatzeffekt-Entscheidung: welche Effekte (Regeltexte/Quellen) ab- bzw. angenommen werden. */
    private static final class ReplMacro {
        final boolean accept;
        final Set<String> rules = new HashSet<>();
        final Set<UUID> sourceIds = new HashSet<>();

        ReplMacro(boolean accept) {
            this.accept = accept;
        }

        void add(PromptDto.ReplGroup g) {
            rules.add(g.rule());
            for (PromptDto.ReplSource s : g.sources()) {
                if (s.objectId() != null) {
                    sourceIds.add(s.objectId());
                }
            }
        }
    }

    /**
     * Game-Thread: beantwortet Ersatzeffekt-Prompts selbst.
     * <ul>
     *   <li>Wahl: Gruppe, die abgelehnt werden soll (laufende Entscheidung oder "merken") -&gt; deren ersten Effekt
     *       waehlen, die Frage danach beantwortet der ASK-Zweig mit Nein. Alle Optionen identisch (Token-Kopien)
     *       -&gt; erste waehlen.</li>
     *   <li>{@code ASK} aus einem Ersatzeffekt ({@link ReplacementAssist#inReplaceEvent}) zu einer Quelle der
     *       laufenden Entscheidung -&gt; Ja/Nein. Auch der letzte Effekt ohne Wahl-Dialog und "merken"-Effekte mit nur
     *       einer Quelle (Regeltext an der Quelle) werden so abgelehnt.</li>
     *   <li>Alles andere beendet die laufende Entscheidung.</li>
     * </ul>
     *
     * @return true, wenn der Prompt beantwortet wurde
     */
    private boolean handleReplacement(HumanSeat seat, PromptDto prompt) {
        ReplMacro m = seat.replMacro;
        List<PromptDto.ReplGroup> groups = "CHOOSE_CHOICE".equals(prompt.kind) && prompt.choice != null ? prompt.choice.groups : null;
        if (groups != null) {
            for (PromptDto.ReplGroup g : groups) {
                boolean declined = (m != null && !m.accept && m.rules.contains(g.rule())) || seat.replDeclineAlways.containsKey(g.rule());
                if (declined && g.optional()) {
                    if (m == null || m.accept) {
                        m = new ReplMacro(false);
                        seat.replMacro = m;
                    }
                    m.add(g);
                    onUpdate();
                    answerInternally(seat, prompt, Response.ofString(g.sources().get(0).key()));
                    return true;
                }
            }
            seat.replMacro = null;
            if (ReplacementAssist.allIdentical(groups)) {
                onUpdate();
                answerInternally(seat, prompt, Response.ofString(groups.get(0).sources().get(0).key()));
                return true;
            }
            return false;
        }
        if ("ASK".equals(prompt.kind) && (m != null || !seat.replDeclineAlways.isEmpty()) && ReplacementAssist.inReplaceEvent()) {
            Set<UUID> ids = ReplacementAssist.objIds(prompt);
            if (m != null && ids.stream().anyMatch(m.sourceIds::contains)) {
                if (m.accept) {
                    seat.replMacro = null;
                }
                onUpdate();
                answerInternally(seat, prompt, Response.ofBool(m.accept));
                return true;
            }
            if (ReplacementAssist.hasRule(game, ids, seat.replDeclineAlways.keySet())) {
                onUpdate();
                answerInternally(seat, prompt, Response.ofBool(false));
                return true;
            }
        }
        seat.replMacro = null;
        return false;
    }

    private static Set<UUID> inCombat(StateDto s, boolean attackers) {
        Set<UUID> out = new HashSet<>();
        if (s != null && s.combat != null) {
            for (var g : s.combat) {
                out.addAll(attackers ? g.attackers() : g.blockers());
            }
        }
        return out;
    }

    public boolean action(HumanSeat seat, String actionName, Object data) {
        PlayerAction action;
        try {
            action = PlayerAction.valueOf(actionName);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!ALLOWED_ACTIONS.contains(action)) {
            return false;
        }
        if (action == PlayerAction.PASS_PRIORITY_CANCEL_ALL_ACTIONS) {
            seat.passedSigs.clear();
        }
        if (action == PlayerAction.CONCEDE) {
            leave(seat);
            return true;
        }
        boolean closesPrompt = action.name().startsWith("PASS_PRIORITY_UNTIL");
        if (closesPrompt) {
            closePromptOf(seat);
        }
        UUID pid = seat.playerId;
        callExecutor.execute(() -> {
            try {
                switch (action) {
                    case MANA_AUTO_PAYMENT_ON -> game.setManaPaymentMode(pid, true);
                    case MANA_AUTO_PAYMENT_OFF -> game.setManaPaymentMode(pid, false);
                    case MANA_AUTO_PAYMENT_RESTRICTED_ON -> game.setManaPaymentModeRestricted(pid, true);
                    case MANA_AUTO_PAYMENT_RESTRICTED_OFF -> game.setManaPaymentModeRestricted(pid, false);
                    case USE_FIRST_MANA_ABILITY_ON -> game.setUseFirstManaAbility(pid, true);
                    case USE_FIRST_MANA_ABILITY_OFF -> game.setUseFirstManaAbility(pid, false);
                    default -> game.sendPlayerAction(action, pid, data);
                }
            } catch (Throwable e) {
                LOG.error("Aktion " + action + " fehlgeschlagen", e);
            }
        });
        return true;
    }

    /** Offenen SELECT-Prompt dieses Sitzes schliessen (XMage beantwortet ihn ueber die Aktion selbst). */
    private void closePromptOf(HumanSeat seat) {
        PromptDto p = openPrompt.get();
        if (p != null && promptSeat == seat && "SELECT".equals(p.kind) && openPrompt.compareAndSet(p, null)) {
            send(seat, new Messages.PromptClosed(p.id));
            notifyOthers(seat, null);
        }
    }

    public void setTempo(TempoSettings.Preset preset) {
        tempo.apply(preset);
    }

    /**
     * Wie GameController.sendMessage: Antwort an den Menschen oder an den von ihm kontrollierten Prioritaetsspieler.
     */
    private void dispatch(HumanSeat seat, Consumer<Player> command) {
        callExecutor.execute(() -> {
            try {
                HumanPlayer human = seat.player;
                if (!human.isGameUnderControl()) {
                    return;
                }
                UUID prio = game.getPriorityPlayerId();
                if (prio == null || prio.equals(seat.playerId)) {
                    command.accept(human);
                } else {
                    for (UUID controlled : human.getPlayersUnderYourControl()) {
                        Player cp = game.getPlayer(controlled);
                        if (cp != null && prio.equals(controlled)) {
                            command.accept(cp);
                        }
                    }
                }
            } catch (Throwable e) {
                LOG.error("Antwort fehlgeschlagen", e);
            }
        });
    }

    // ------------------------------------------------------------------ Engine-Events (Game-Thread)

    private void onTableEvent(TableEvent event) {
        try {
            switch (event.getEventType()) {
                case UPDATE -> onUpdate();
                case INFO, STATUS -> addLog(event.getEventType().name(), event.getMessage());
                case ERROR -> {
                    LOG.error("Engine-Fehler: " + event.getMessage(), event.getException());
                    emit(new Messages.Toast("error", RichText.parse(event.getMessage())));
                }
                default -> {
                    // Timer, Turnier etc. nicht relevant
                }
            }
        } catch (Throwable e) {
            LOG.error("TableEvent " + event.getEventType() + " fehlgeschlagen", e);
        }
    }

    private void onQueryEvent(PlayerQueryEvent event) {
        try {
            Player player = game.getPlayer(event.getPlayerId());
            if (player == null) {
                return;
            }
            UUID controller = player.getTurnControlledBy();
            HumanSeat seat = humans.get(controller);
            if (seat == null) {
                if (player instanceof MageLiteBot bot && bot.isQuickPassing()) {
                    return; // passt ohne nachzudenken: kein "denkt", kein State
                }
                // Bots feuern SELECT bei jeder Prioritaet -> "denkt"-Signal (nur wenn der Bot wirklich rechnet)
                boolean realThink = player.getId().equals(game.getActivePlayerId()) || !game.getStack().isEmpty()
                        || !tempo.fastOpponentTurns();
                if (event.getQueryType() == PlayerQueryEvent.QueryType.SELECT && realThink && !player.getId().equals(thinking)) {
                    thinking = player.getId();
                    flushStateIfDirty();
                    emit(new Messages.Status(thinking, false, player.getName()));
                }
                return;
            }
            if (event.getQueryType() == PlayerQueryEvent.QueryType.PERSONAL_MESSAGE) {
                send(seat, new Messages.Toast("info", RichText.parse(event.getMessage())));
                return;
            }
            lastHumanQueryAt = System.currentTimeMillis();
            thinking = null;
            PromptDto prompt = PromptMapper.map(game, event, seat.playerId);
            if (prompt == null) {
                return;
            }

            CombatMacro m = seat.macro;
            if (m != null && continueMacro(seat, m, prompt)) {
                return;
            }

            if (handleReplacement(seat, prompt)) {
                return;
            }

            if (handleAutoPay(seat, prompt)) {
                return;
            }

            boolean priorityPrompt = "SELECT".equals(prompt.kind) && "priority".equals(prompt.mode);
            String sig = priorityPrompt ? StackSig.top(game) : null;
            if (sig != null && seat.passedSigs.contains(sig)) {
                // auf ein gleiches Stapelobjekt schon gepasst -> wieder passen (ohne vollen State)
                onUpdate();
                answerInternally(seat, prompt, Response.ofBool(false));
                return;
            }
            GameViewMapper.Playable playable = null;
            if (priorityPrompt) {
                playable = GameViewMapper.playable(game, seat.player);
                if (seat.autoPass && !playable.hasActions()) {
                    // nichts spielbar (ausser Mana) -> automatisch passen; State nur gedrosselt
                    onUpdate();
                    answerInternally(seat, prompt, Response.ofBool(false));
                    return;
                }
            }
            StateDto state = sendState(seat, playable);

            prompt.id = promptSeq.incrementAndGet();
            prompt.stateSeq = state.seq;
            seat.promptSig = sig;
            seat.promptSigId = prompt.id;
            promptSeat = seat;
            openPrompt.set(prompt);
            send(seat, prompt);
            notifyOthers(seat, seat.name());
        } catch (Throwable e) {
            LOG.error("QueryEvent " + event.getQueryType() + " fehlgeschlagen", e);
        }
    }

    /** Den anderen Menschen sagen, auf wen gewartet wird (null = niemand mehr). */
    private void notifyOthers(HumanSeat seat, String waitingFor) {
        if (humans.size() < 2) {
            return;
        }
        Messages.Status st = new Messages.Status(null, false, waitingFor);
        for (HumanSeat o : humans.values()) {
            if (o != seat) {
                send(o, st);
            }
        }
    }

    /** Mehrfach-Angriff/-Block: Warteschlange der markierten Kreaturen und gemeinsames Ziel. */
    private static final class CombatMacro {
        final String mode;
        final Deque<UUID> queue;
        final UUID target;
        /** zuletzt angeklickte Kreatur (wartet ggf. auf die Zielabfrage) */
        UUID current;

        CombatMacro(String mode, Deque<UUID> queue, UUID target) {
            this.mode = mode;
            this.queue = queue;
            this.target = target;
        }
    }

    /**
     * Game-Thread: beantwortet die Prompts eines Mehrfach-Angriffs/-Blocks selbst.
     * <ul>
     *   <li>{@code SELECT} im gleichen Modus: naechste markierte Kreatur anklicken (bereits angreifende/blockende
     *       ueberspringen - ein erneuter Klick wuerde sie bei XMage wieder entfernen). Keine mehr: normaler Prompt.</li>
     *   <li>{@code PICK_TARGET} (Verteidiger bzw. Angreifer zum Blocken): das gemeinsame Ziel, falls waehlbar.</li>
     *   <li>Alles andere (Kosten, Ziel nicht waehlbar ...): abbrechen und den Prompt normal zeigen. Nie mit "Abbrechen"
     *       antworten - bei Pflicht-Zielen fragt XMage dann endlos neu.</li>
     * </ul>
     *
     * @return true, wenn der Prompt beantwortet wurde
     */
    private boolean continueMacro(HumanSeat seat, CombatMacro m, PromptDto prompt) {
        boolean attack = "attackers".equals(m.mode);
        if ("SELECT".equals(prompt.kind) && m.mode.equals(prompt.mode)) {
            List<UUID> possibleList = attack ? prompt.possibleAttackers : prompt.possibleBlockers;
            Set<UUID> possible = possibleList == null ? Set.of() : new HashSet<>(possibleList);
            Set<UUID> busy = new HashSet<>(attack ? game.getCombat().getAttackers() : game.getCombat().getBlockers());
            UUID next;
            do {
                next = m.queue.poll();
            } while (next != null && (!possible.contains(next) || busy.contains(next)));
            if (next == null) {
                seat.macro = null;
                return false; // fertig -> normaler Prompt (Angriff bestaetigen)
            }
            m.current = next;
            onUpdate();
            answerInternally(seat, prompt, Response.ofUuid(next));
            return true;
        }
        if ("PICK_TARGET".equals(prompt.kind) && m.current != null && prompt.targets != null && prompt.targets.contains(m.target)) {
            m.current = null;
            onUpdate();
            answerInternally(seat, prompt, Response.ofUuid(m.target));
            return true;
        }
        seat.macro = null;
        LOG.info("Mehrfach-" + (attack ? "Angriff" : "Block") + " angehalten bei " + prompt.kind + ": " + prompt.messageText);
        send(seat, new Messages.Toast("info", RichText.parse(attack
                ? "Mehrfach-Angriff angehalten – bitte hier selbst entscheiden."
                : "Mehrfach-Block angehalten – bitte hier selbst entscheiden.")));
        return false;
    }

    /** Auto-Antwort ohne promptClosed-Nachricht an den Client. */
    private void respondInternal(HumanSeat seat, long promptId, Response r) {
        PromptDto p = openPrompt.get();
        if (p == null || p.id != promptId || promptSeat != seat || !openPrompt.compareAndSet(p, null)) {
            return;
        }
        dispatch(seat, target -> apply(seat, target, r));
    }

    /** Setzt die Antwort (nur CALL-Thread). Wartet vorher, bis XMage wirklich auf sie wartet. */
    private void apply(HumanSeat seat, Player target, Response r) {
        awaitHumanWaiting();
        setResponse(seat, target, r);
        lastAppliedSeat = seat;
        lastAppliedTarget = target;
        lastApplied = r;
        lastAppliedAt = System.currentTimeMillis();
        recoverAttempts = 0;
    }

    private static void setResponse(HumanSeat seat, Player target, Response r) {
        if (r.uuid() != null) {
            target.setResponseUUID(r.uuid());
        } else if (r.bool() != null) {
            target.setResponseBoolean(r.bool());
        } else if (r.integer() != null) {
            target.setResponseInteger(r.integer());
        } else if (r.string() != null) {
            target.setResponseString(r.string());
        } else if (r.manaType() != null) {
            target.setResponseManaType(r.manaPlayerId() == null ? seat.playerId : r.manaPlayerId(), r.manaType());
        } else {
            target.setResponseBoolean(false);
        }
    }

    private void answerInternally(HumanSeat seat, PromptDto prompt, Response r) {
        prompt.id = promptSeq.incrementAndGet();
        promptSeat = seat;
        openPrompt.set(prompt);
        respondInternal(seat, prompt.id, r);
    }

    // ------------------------------------------------------------------ Auto-Bezahlen

    public void setAutoPayDefault(HumanSeat seat, boolean on) {
        seat.autoPayDefault = on;
    }

    /** Client-Knopf "Auto bezahlen" bei offenem Mana-Prompt (Game-Thread wartet gerade). */
    public void autoPayNow(HumanSeat seat) {
        PromptDto p = openPrompt.get();
        if (p == null || promptSeat != seat || !"PLAY_MANA".equals(p.kind)) {
            return;
        }
        callExecutor.execute(() -> {
            AutoPayer.Step st = AutoPayer.next(game, seat.playerId, p.messageText, GameViewMapper.playable(game, seat.player).all().keySet());
            if (st == null) {
                send(seat, new Messages.Toast("info", RichText.parse("Automatisches Bezahlen nicht möglich – bitte Manaquellen anklicken.")));
                // Client hat den Prompt schon als beantwortet markiert -> erneut zustellen, sonst steht die UI
                if (openPrompt.get() == p) {
                    send(seat, p);
                }
                return;
            }
            seat.autoPayActive = true;
            seat.autoPayFailed = false;
            seat.autoPaySteps = 1;
            seat.autoPayLastMsg = p.messageText;
            seat.autoPayColor = st.color();
            if (respondDirect(seat, p.id, Response.ofUuid(st.sourceId()))) {
                send(seat, new Messages.PromptClosed(p.id));
            }
        });
    }

    /** Antwort vom CALL-Thread aus (ohne erneutes Einreihen). */
    private boolean respondDirect(HumanSeat seat, long promptId, Response r) {
        PromptDto p = openPrompt.get();
        if (p == null || p.id != promptId || promptSeat != seat || !openPrompt.compareAndSet(p, null)) {
            return false;
        }
        if (seat.player.isGameUnderControl()) {
            apply(seat, seat.player, r);
        }
        return true;
    }

    /**
     * @return true, wenn der Prompt automatisch beantwortet wurde
     */
    private boolean handleAutoPay(HumanSeat seat, PromptDto prompt) {
        switch (prompt.kind) {
            case "PLAY_MANA" -> {
                if (!seat.autoPayActive && (!seat.autoPayDefault || seat.autoPayFailed)) {
                    return false;
                }
                if (!seat.autoPayActive) {
                    seat.autoPayActive = true;
                    seat.autoPaySteps = 0;
                    seat.autoPayLastMsg = null;
                }
                if (seat.autoPaySteps++ > 30 || prompt.messageText.equals(seat.autoPayLastMsg)) {
                    stopAutoPay(seat, true);
                    return false;
                }
                seat.autoPayLastMsg = prompt.messageText;
                AutoPayer.Step st = AutoPayer.next(game, seat.playerId, prompt.messageText, GameViewMapper.playable(game, seat.player).all().keySet());
                if (st == null) {
                    stopAutoPay(seat, true);
                    return false;
                }
                seat.autoPayColor = st.color();
                answerInternally(seat, prompt, Response.ofUuid(st.sourceId()));
                return true;
            }
            case "CHOOSE_CHOICE" -> {
                if (seat.autoPayActive && seat.autoPayColor != null && prompt.choice != null && prompt.choice.manaColor) {
                    String want = AutoPayer.colorName(seat.autoPayColor);
                    for (PromptDto.ChoiceItem it : prompt.choice.items) {
                        if (want.equalsIgnoreCase(it.value()) || want.equalsIgnoreCase(it.key())) {
                            answerInternally(seat, prompt, Response.ofString(prompt.choice.keyed ? it.key() : it.value()));
                            return true;
                        }
                    }
                }
                return false;
            }
            case "CHOOSE_ABILITY" -> {
                if (seat.autoPayActive && seat.autoPayColor != null && prompt.choices != null && !prompt.choices.isEmpty()) {
                    String sym = "{" + seat.autoPayColor.name() + "}";
                    PromptDto.Item pick = prompt.choices.stream().filter(i -> i.text().contains(sym)).findFirst()
                            .orElse(prompt.choices.stream().filter(i -> i.text().contains("Add")).findFirst().orElse(null));
                    if (pick != null) {
                        answerInternally(seat, prompt, Response.ofUuid(UUID.fromString(pick.id())));
                        return true;
                    }
                }
                return false;
            }
            default -> {
                if (seat.autoPayActive) {
                    stopAutoPay(seat, false);
                }
                seat.autoPayFailed = false;
                return false;
            }
        }
    }

    private static void stopAutoPay(HumanSeat seat, boolean failed) {
        seat.autoPayActive = false;
        seat.autoPayColor = null;
        if (failed) {
            seat.autoPayFailed = true;
        }
    }

    // ------------------------------------------------------------------ States

    private void onUpdate() {
        if (game.getStack().isEmpty()) {
            for (HumanSeat s : humans.values()) {
                if (!s.passedSigs.isEmpty()) {
                    s.passedSigs.clear();
                }
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastStateAt >= STATE_MIN_INTERVAL_MS) {
            sendState(null, null);
        } else {
            stateDirty = true;
        }
    }

    private void flushStateIfDirty() {
        if (stateDirty && ThreadUtils.isRunGameThread()) {
            sendState(null, null);
        }
    }

    /**
     * Baut und sendet jedem Menschen seinen State. {@code forSeat} bekommt die spielbaren Objekte dazu (der teure Teil),
     * optional mit vorberechnetem {@code playable}.
     *
     * @return der State von {@code forSeat} (bzw. des ersten Menschen)
     */
    private StateDto sendState(HumanSeat forSeat, GameViewMapper.Playable playable) {
        trackEliminations();
        long seq = stateSeq.incrementAndGet();
        StateDto mine = null;
        for (HumanSeat s : humans.values()) {
            boolean withPlayable = s == forSeat;
            StateDto st = GameViewMapper.map(game, s.playerId, seq, withPlayable, withPlayable ? playable : null, thinking, deckNames);
            if (!s.replDeclineAlways.isEmpty()) {
                st.replDeclines = new ArrayList<>(new LinkedHashSet<>(s.replDeclineAlways.values()));
            }
            s.lastState = st;
            send(s, st);
            if (s == forSeat || (mine == null && forSeat == null)) {
                mine = st;
            }
        }
        lastStateAt = System.currentTimeMillis();
        stateDirty = false;
        return mine;
    }

    private void trackEliminations() {
        for (Player p : game.getState().getPlayers().values()) {
            if ((p.hasLost() || p.hasLeft()) && !eliminatedTurn.containsKey(p.getId())) {
                eliminatedTurn.put(p.getId(), game.getTurnNum());
                eliminationOrder.add(p.getId());
            }
        }
    }

    private void addLog(String kind, String html) {
        if (html == null || html.isBlank()) {
            return;
        }
        Messages.LogEntry entry = new Messages.LogEntry(System.currentTimeMillis(), game.getTurnNum(), activePlayerName(), kind, RichText.parse(html));
        synchronized (logTail) {
            logTail.addLast(entry);
            while (logTail.size() > LOG_KEEP) {
                logTail.removeFirst();
            }
        }
        emit(new Messages.Log(List.of(entry)));
    }

    private String activePlayerName() {
        Player p = game.getPlayer(game.getActivePlayerId());
        return p == null ? null : p.getName();
    }

    /** An alle Menschen. */
    private void emit(Object msg) {
        for (HumanSeat s : humans.values()) {
            send(s, msg);
        }
    }

    /** An einen Sitz. */
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

    // ------------------------------------------------------------------ Wachhund (XMage-Race, Aktivitaet)

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /**
     * XMage-Race umgehen: {@code HumanPlayer.waitForResponse} setzt {@code responseOpenedForAnswer = true} bevor es in
     * {@code response.wait()} geht. Kommt die Antwort dazwischen, verpufft {@code notifyAll()} und das Spiel wartet
     * ewig. Darum erst antworten, wenn der Spiel-Thread wirklich wartet (oder nach Timeout wie bisher).
     */
    private void awaitHumanWaiting() {
        Thread t = gameThread;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        long deadline = System.currentTimeMillis() + AWAIT_WAITING_MS;
        while (t.isAlive() && System.currentTimeMillis() < deadline) {
            if (inWaitForResponse(t)) {
                return;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Spiel-Thread steckt in {@code HumanPlayer.waitForResponse} -> {@code Object.wait()}. */
    private static boolean inWaitForResponse(Thread t) {
        if (t.getState() != Thread.State.WAITING) {
            return false;
        }
        for (StackTraceElement e : t.getStackTrace()) {
            if ("waitForResponse".equals(e.getMethodName()) && e.getClassName().startsWith("mage.player.human.")) {
                return true;
            }
        }
        return false;
    }

    private void watchdogTick() {
        try {
            Thread t = gameThread;
            if (t == null || !t.isAlive()) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean waiting = inWaitForResponse(t);
            if (waiting && looksLost(now)) {
                callExecutor.execute(this::recoverLostResponse);
            }
            // getrennte Mitspieler: Zaehler fuer die anderen alle 2 s aktualisieren
            if (ticks % 4 == 0 && humans.size() > 1
                    && humans.values().stream().anyMatch(s -> !s.conceded && !s.connected())) {
                broadcastSeats();
            }
            if (++ticks % 2 == 0) {
                sendActivity(t, waiting, now);
            }
        } catch (Throwable e) {
            LOG.warn("Wachhund: " + e);
        }
    }

    /** Antwort gesetzt, seitdem keine neue Frage, kein Fortschritt, aber XMage wartet noch -> Antwort verloren. */
    private boolean looksLost(long now) {
        return lastApplied != null && openPrompt.get() == null && lastAppliedAt > lastHumanQueryAt
                && now - lastAppliedAt > RECOVER_AFTER_MS && now - lastProgressAt > RECOVER_AFTER_MS;
    }

    /** CALL-Thread: letzte Antwort erneut zustellen (weckt den wartenden Spiel-Thread). */
    private void recoverLostResponse() {
        Thread t = gameThread;
        Player target = lastAppliedTarget;
        HumanSeat seat = lastAppliedSeat;
        Response r = lastApplied;
        if (t == null || target == null || seat == null || r == null || recoverAttempts >= RECOVER_MAX_PER_ANSWER
                || !looksLost(System.currentTimeMillis()) || !inWaitForResponse(t)) {
            return;
        }
        recoverAttempts++;
        recovered++;
        LOG.warn("Antwort ging verloren (XMage-Race) - stelle erneut zu: " + r);
        setResponse(seat, target, r);
        lastAppliedAt = System.currentTimeMillis();
    }

    /** Herzschlag pro Sitz: "you" nur fuer den Besitzer des offenen Prompts, die anderen sehen "human" + Name. */
    private void sendActivity(Thread t, boolean waiting, long now) {
        int cpu = cpuPercent(now);
        long idle = now - lastProgressAt;
        UUID th = thinking;
        HumanSeat owner = waiting && openPrompt.get() != null ? promptSeat : null;
        String mode;
        String who = null;
        if (owner != null) {
            mode = "you";
        } else if (th != null) {
            mode = "bot";
            Player p = game.getPlayer(th);
            who = p == null ? null : p.getName();
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
                send(s, new Messages.Activity("human", owner.name(), cpu, idle, recovered));
            } else {
                send(s, new Messages.Activity(mode, who, cpu, idle, recovered));
            }
        }
    }

    /** CPU-Last aller Engine-Threads seit dem letzten Aufruf in % eines Kerns; -1 wenn nicht messbar. */
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
        List<Player> all = new ArrayList<>(game.getState().getPlayers().values());
        // Platzierung: Sieger 1, danach in umgekehrter Ausscheide-Reihenfolge; Unentschieden/Ueberlebende teilen Platz
        Map<UUID, Integer> place = new LinkedHashMap<>();
        UUID winnerId = null;
        for (Player p : all) {
            if (p.hasWon()) {
                winnerId = p.getId();
                place.put(p.getId(), 1);
            }
        }
        int survivors = 0;
        for (Player p : all) {
            if (!place.containsKey(p.getId()) && !eliminatedTurn.containsKey(p.getId())) {
                survivors++;
            }
        }
        int nextPlace = winnerId == null ? 1 : 2;
        for (Player p : all) {
            if (!place.containsKey(p.getId()) && !eliminatedTurn.containsKey(p.getId())) {
                place.put(p.getId(), nextPlace);
            }
        }
        nextPlace += survivors;
        for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
            UUID pid = eliminationOrder.get(i);
            if (!place.containsKey(pid)) {
                place.put(pid, nextPlace++);
            }
        }
        List<Messages.Placement> placements = new ArrayList<>();
        for (Player p : all) {
            placements.add(new Messages.Placement(p.getId(), p.getName(), place.getOrDefault(p.getId(), all.size()),
                    humans.containsKey(p.getId()), p.getLife(), eliminatedTurn.get(p.getId()),
                    match.getMulligan() == null ? 0 : match.getMulligan().getMulliganCount(p.getId())));
        }
        placements.sort((a, b) -> Integer.compare(a.place(), b.place()));
        return new Messages.GameOver(winnerId, game.getWinner(), placements, game.getTurnNum(),
                System.currentTimeMillis() - startedAt, null, error);
    }

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
        return new LinkedHashMap<>(eliminatedTurn);
    }

    public MageLiteMatch getMatch() {
        return match;
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
        callExecutor.shutdownNow();
        try {
            callExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
