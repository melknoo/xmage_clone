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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
 * Fuehrt ein Spiel (1 Mensch + 3 Bots) ohne XMage-Server aus. Ersetzt GameController/GameSessionPlayer.
 * <p>
 * Threads:
 * <ul>
 *   <li>Game-Thread ({@code GAME <id>}): {@code game.start()}, alle Listener, GameView-Bau.</li>
 *   <li>CALL-Thread: einziger Aufrufer von {@code setResponse*}/{@code sendPlayerAction}. Antworten
 *       vom Game-Thread wuerden 30 s blockieren und verworfen.</li>
 * </ul>
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

    private final UUID id = UUID.randomUUID();
    private final MageLiteMatch match;
    private final Game game;
    private final HumanPlayer human;
    private final UUID humanId;
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

    private volatile Sink sink = msg -> {
    };
    private volatile StateDto lastState;
    /** offener Prompt des Menschen; Antworten raeumen ihn per compareAndSet (genau eine Antwort pro Prompt) */
    private final AtomicReference<PromptDto> openPrompt = new AtomicReference<>();
    private volatile UUID thinking;
    private volatile boolean autoPass = true;
    private volatile Messages.GameOver gameOver;
    private volatile Consumer<GameHost> onFinished;
    private volatile java.util.function.BiFunction<GameHost, Messages.GameOver, Object> rewardHook;
    private volatile boolean humanConceded;
    /**
     * Stapelobjekte, auf die ich schon gepasst habe ({@link StackSig}); gleiche danach automatisch passen.
     * Wird geleert, sobald der Stapel leer ist, und mit F3.
     */
    private final Set<String> passedSigs = ConcurrentHashMap.newKeySet();
    /** Signatur des obersten Stapelobjekts zum offenen Prioritaets-Prompt {@link #promptSigId} */
    private volatile String promptSig;
    private volatile long promptSigId;
    /** laufender Mehrfach-Angriff/-Block (Game-Thread, gestartet vom WS-Thread) */
    private volatile CombatMacro macro;
    // Auto-Bezahlen (Game-Thread)
    private volatile boolean autoPayDefault = true;
    private boolean autoPayActive;
    private boolean autoPayFailed;
    private AutoPayer.Color autoPayColor;
    private int autoPaySteps;
    private String autoPayLastMsg;
    private long lastStateAt;
    private boolean stateDirty;
    private volatile Thread gameThread;
    private long startedAt;
    // Wachhund / Aktivitaet
    private volatile long lastProgressAt = System.currentTimeMillis();
    private volatile long lastHumanQueryAt;
    private volatile Player lastAppliedTarget;
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

        human = new HumanPlayer(setup.humanName(), RangeOfInfluence.ALL, 0);
        human.setUserData(HumanSettings.defaults());
        humanId = human.getId();
        addSeat(human, setup.humanDeck(), true);

        Set<String> usedNames = new java.util.HashSet<>();
        usedNames.add(setup.humanName());
        for (LoadedDeck deck : setup.botDecks()) {
            String name = botName(deck, usedNames);
            MageLiteBot bot = new MageLiteBot(name, RangeOfInfluence.ALL, tempo);
            bot.setHooks((g, b) -> flushStateIfDirty());
            bots.add(bot);
            addSeat(bot, deck, false);
        }

        match.startMatch();
        match.startGame();
        game = match.getGame();
        GameOptions options = GameOptions.getDefault().copy();
        options.rollbackTurnsAllowed = false;
        game.setGameOptions(options);
        game.addTableEventListener(this::onTableEvent);
        game.addPlayerQueryEventListener(this::onQueryEvent);
        StatsSink.register(game.getId(), humanId);
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

    public UUID getHumanId() {
        return humanId;
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

    public void setAutoPass(boolean autoPass) {
        this.autoPass = autoPass;
    }

    /**
     * Verbindet einen Client und schickt den aktuellen Stand (Resync nach Reconnect).
     */
    public synchronized void attach(Sink newSink) {
        this.sink = newSink;
        newSink.send(hello());
        synchronized (logTail) {
            if (!logTail.isEmpty()) {
                newSink.send(new Messages.Log(new ArrayList<>(logTail)));
            }
        }
        StateDto s = lastState;
        if (s != null) {
            newSink.send(s);
        }
        PromptDto p = openPrompt.get();
        if (p != null) {
            newSink.send(p);
        }
        Messages.GameOver over = gameOver;
        if (over != null) {
            newSink.send(over);
        }
    }

    public void detach(Sink oldSink) {
        if (this.sink == oldSink) {
            this.sink = msg -> {
            };
        }
    }

    public Messages.Hello hello() {
        return new Messages.Hello(id, humanId, seats, tempo.preset().name());
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
            sendState(false);
            gameOver = buildGameOver(error);
            var hook = rewardHook;
            if (hook != null) {
                try {
                    Object reward = hook.apply(this, gameOver);
                    if (reward != null) {
                        gameOver = new Messages.GameOver(gameOver.winnerId(), gameOver.result(), gameOver.placements(), gameOver.turns(),
                                gameOver.durationMs(), reward, gameOver.error());
                    }
                } catch (Throwable e) {
                    LOG.error("Belohnung fehlgeschlagen", e);
                }
            }
            emit(gameOver);
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
        humanConceded = true;
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
     * Antwort auf den offenen Prompt. Ignoriert veraltete promptIds (Doppelklicks).
     */
    public boolean respond(long promptId, Response r) {
        PromptDto p = openPrompt.get();
        if (p == null || p.id != promptId || !openPrompt.compareAndSet(p, null)) {
            return false;
        }
        macro = null;
        String sig = promptSig;
        if (sig != null && promptSigId == promptId && Boolean.FALSE.equals(r.bool())) {
            // gepasst -> gleiche Stapelobjekte laufen ab jetzt automatisch durch
            passedSigs.add(sig);
        }
        emit(new Messages.PromptClosed(promptId));
        dispatch(target -> apply(target, r));
        return true;
    }

    /**
     * Mehrfach-Angriff/-Block: {@code ids} greifen {@code target} an (Angriffs-Prompt) bzw. blocken den Angreifer
     * {@code target} (Block-Prompt). Die Folge-Prompts beantwortet {@link #continueMacro}.
     */
    public boolean combat(List<UUID> ids, UUID target) {
        PromptDto p = openPrompt.get();
        if (p == null) {
            return false;
        }
        boolean attack = "attackers".equals(p.mode);
        if (!"SELECT".equals(p.kind) || target == null || ids == null || (!attack && !"blockers".equals(p.mode))) {
            emit(p); // Client hat den Prompt schon als beantwortet markiert -> erneut zustellen
            return false;
        }
        List<UUID> possible = attack ? p.possibleAttackers : p.possibleBlockers;
        Set<UUID> busy = inCombat(lastState, attack);
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
                emit(p);
            }
            return false;
        }
        CombatMacro m = new CombatMacro(p.mode, queue, target);
        m.current = first;
        macro = m;
        emit(new Messages.PromptClosed(p.id));
        dispatch(t -> apply(t, Response.ofUuid(first)));
        return true;
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

    public boolean action(String actionName, Object data) {
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
            passedSigs.clear();
        }
        boolean closesPrompt = action.name().startsWith("PASS_PRIORITY_UNTIL") || action == PlayerAction.CONCEDE;
        PromptDto p = openPrompt.get();
        if (closesPrompt && p != null && "SELECT".equals(p.kind) && openPrompt.compareAndSet(p, null)) {
            emit(new Messages.PromptClosed(p.id));
        }
        callExecutor.execute(() -> {
            try {
                switch (action) {
                    case CONCEDE -> {
                        humanConceded = true;
                        game.informPlayers(human.getLogName() + " gibt auf");
                        game.setConcedingPlayer(humanId);
                    }
                    case MANA_AUTO_PAYMENT_ON -> game.setManaPaymentMode(humanId, true);
                    case MANA_AUTO_PAYMENT_OFF -> game.setManaPaymentMode(humanId, false);
                    case MANA_AUTO_PAYMENT_RESTRICTED_ON -> game.setManaPaymentModeRestricted(humanId, true);
                    case MANA_AUTO_PAYMENT_RESTRICTED_OFF -> game.setManaPaymentModeRestricted(humanId, false);
                    case USE_FIRST_MANA_ABILITY_ON -> game.setUseFirstManaAbility(humanId, true);
                    case USE_FIRST_MANA_ABILITY_OFF -> game.setUseFirstManaAbility(humanId, false);
                    default -> game.sendPlayerAction(action, humanId, data);
                }
            } catch (Throwable e) {
                LOG.error("Aktion " + action + " fehlgeschlagen", e);
            }
        });
        return true;
    }

    public void setTempo(TempoSettings.Preset preset) {
        tempo.apply(preset);
    }

    /**
     * Wie GameController.sendMessage: Antwort an mich oder an den von mir kontrollierten Prioritaetsspieler.
     */
    private void dispatch(Consumer<Player> command) {
        callExecutor.execute(() -> {
            try {
                if (!human.isGameUnderControl()) {
                    return;
                }
                UUID prio = game.getPriorityPlayerId();
                if (prio == null || prio.equals(humanId)) {
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
            if (!humanId.equals(controller)) {
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
                emit(new Messages.Toast("info", RichText.parse(event.getMessage())));
                return;
            }
            lastHumanQueryAt = System.currentTimeMillis();
            thinking = null;
            PromptDto prompt = PromptMapper.map(game, event, humanId);
            if (prompt == null) {
                return;
            }

            CombatMacro m = macro;
            if (m != null && continueMacro(m, prompt)) {
                return;
            }

            if (handleAutoPay(prompt)) {
                return;
            }

            boolean priorityPrompt = "SELECT".equals(prompt.kind) && "priority".equals(prompt.mode);
            String sig = priorityPrompt ? StackSig.top(game) : null;
            if (sig != null && passedSigs.contains(sig)) {
                // auf ein gleiches Stapelobjekt schon gepasst -> wieder passen (ohne vollen State)
                onUpdate();
                answerInternally(prompt, Response.ofBool(false));
                return;
            }
            GameViewMapper.Playable playable = null;
            if (priorityPrompt) {
                playable = GameViewMapper.playable(game, human);
                if (autoPass && !playable.hasActions()) {
                    // nichts spielbar (ausser Mana) -> automatisch passen; State nur gedrosselt
                    onUpdate();
                    answerInternally(prompt, Response.ofBool(false));
                    return;
                }
            }
            StateDto state = sendState(true, playable);

            prompt.id = promptSeq.incrementAndGet();
            prompt.stateSeq = state.seq;
            promptSig = sig;
            promptSigId = prompt.id;
            openPrompt.set(prompt);
            emit(prompt);
        } catch (Throwable e) {
            LOG.error("QueryEvent " + event.getQueryType() + " fehlgeschlagen", e);
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
    private boolean continueMacro(CombatMacro m, PromptDto prompt) {
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
                macro = null;
                return false; // fertig -> normaler Prompt (Angriff bestaetigen)
            }
            m.current = next;
            onUpdate();
            answerInternally(prompt, Response.ofUuid(next));
            return true;
        }
        if ("PICK_TARGET".equals(prompt.kind) && m.current != null && prompt.targets != null && prompt.targets.contains(m.target)) {
            m.current = null;
            onUpdate();
            answerInternally(prompt, Response.ofUuid(m.target));
            return true;
        }
        macro = null;
        LOG.info("Mehrfach-" + (attack ? "Angriff" : "Block") + " angehalten bei " + prompt.kind + ": " + prompt.messageText);
        emit(new Messages.Toast("info", RichText.parse(attack
                ? "Mehrfach-Angriff angehalten – bitte hier selbst entscheiden."
                : "Mehrfach-Block angehalten – bitte hier selbst entscheiden.")));
        return false;
    }

    /** Auto-Antwort ohne promptClosed-Nachricht an den Client. */
    private void respondInternal(long promptId, Response r) {
        PromptDto p = openPrompt.get();
        if (p == null || p.id != promptId || !openPrompt.compareAndSet(p, null)) {
            return;
        }
        dispatch(target -> apply(target, r));
    }

    /** Setzt die Antwort (nur CALL-Thread). Wartet vorher, bis XMage wirklich auf sie wartet. */
    private void apply(Player target, Response r) {
        awaitHumanWaiting();
        setResponse(target, r);
        lastAppliedTarget = target;
        lastApplied = r;
        lastAppliedAt = System.currentTimeMillis();
        recoverAttempts = 0;
    }

    private void setResponse(Player target, Response r) {
        if (r.uuid() != null) {
            target.setResponseUUID(r.uuid());
        } else if (r.bool() != null) {
            target.setResponseBoolean(r.bool());
        } else if (r.integer() != null) {
            target.setResponseInteger(r.integer());
        } else if (r.string() != null) {
            target.setResponseString(r.string());
        } else if (r.manaType() != null) {
            target.setResponseManaType(r.manaPlayerId() == null ? humanId : r.manaPlayerId(), r.manaType());
        } else {
            target.setResponseBoolean(false);
        }
    }

    private void answerInternally(PromptDto prompt, Response r) {
        prompt.id = promptSeq.incrementAndGet();
        openPrompt.set(prompt);
        respondInternal(prompt.id, r);
    }

    // ------------------------------------------------------------------ Auto-Bezahlen

    public void setAutoPayDefault(boolean on) {
        this.autoPayDefault = on;
    }

    /** Client-Knopf "Auto bezahlen" bei offenem Mana-Prompt (Game-Thread wartet gerade). */
    public void autoPayNow() {
        PromptDto p = openPrompt.get();
        if (p == null || !"PLAY_MANA".equals(p.kind)) {
            return;
        }
        callExecutor.execute(() -> {
            AutoPayer.Step st = AutoPayer.next(game, humanId, p.messageText, GameViewMapper.playable(game, human).all().keySet());
            if (st == null) {
                emit(new Messages.Toast("info", RichText.parse("Automatisches Bezahlen nicht möglich – bitte Manaquellen anklicken.")));
                // Client hat den Prompt schon als beantwortet markiert -> erneut zustellen, sonst steht die UI
                if (openPrompt.get() == p) {
                    emit(p);
                }
                return;
            }
            autoPayActive = true;
            autoPayFailed = false;
            autoPaySteps = 1;
            autoPayLastMsg = p.messageText;
            autoPayColor = st.color();
            if (respondDirect(p.id, Response.ofUuid(st.sourceId()))) {
                emit(new Messages.PromptClosed(p.id));
            }
        });
    }

    /** Antwort vom CALL-Thread aus (ohne erneutes Einreihen). */
    private boolean respondDirect(long promptId, Response r) {
        PromptDto p = openPrompt.get();
        if (p == null || p.id != promptId || !openPrompt.compareAndSet(p, null)) {
            return false;
        }
        if (human.isGameUnderControl()) {
            apply(human, r);
        }
        return true;
    }

    /**
     * @return true, wenn der Prompt automatisch beantwortet wurde
     */
    private boolean handleAutoPay(PromptDto prompt) {
        switch (prompt.kind) {
            case "PLAY_MANA" -> {
                if (!autoPayActive && (!autoPayDefault || autoPayFailed)) {
                    return false;
                }
                if (!autoPayActive) {
                    autoPayActive = true;
                    autoPaySteps = 0;
                    autoPayLastMsg = null;
                }
                if (autoPaySteps++ > 30 || prompt.messageText.equals(autoPayLastMsg)) {
                    stopAutoPay(true);
                    return false;
                }
                autoPayLastMsg = prompt.messageText;
                AutoPayer.Step st = AutoPayer.next(game, humanId, prompt.messageText, GameViewMapper.playable(game, human).all().keySet());
                if (st == null) {
                    stopAutoPay(true);
                    return false;
                }
                autoPayColor = st.color();
                answerInternally(prompt, Response.ofUuid(st.sourceId()));
                return true;
            }
            case "CHOOSE_CHOICE" -> {
                if (autoPayActive && autoPayColor != null && prompt.choice != null && prompt.choice.manaColor) {
                    String want = AutoPayer.colorName(autoPayColor);
                    for (PromptDto.ChoiceItem it : prompt.choice.items) {
                        if (want.equalsIgnoreCase(it.value()) || want.equalsIgnoreCase(it.key())) {
                            answerInternally(prompt, Response.ofString(prompt.choice.keyed ? it.key() : it.value()));
                            return true;
                        }
                    }
                }
                return false;
            }
            case "CHOOSE_ABILITY" -> {
                if (autoPayActive && autoPayColor != null && prompt.choices != null && !prompt.choices.isEmpty()) {
                    String sym = "{" + autoPayColor.name() + "}";
                    PromptDto.Item pick = prompt.choices.stream().filter(i -> i.text().contains(sym)).findFirst()
                            .orElse(prompt.choices.stream().filter(i -> i.text().contains("Add")).findFirst().orElse(null));
                    if (pick != null) {
                        answerInternally(prompt, Response.ofUuid(UUID.fromString(pick.id())));
                        return true;
                    }
                }
                return false;
            }
            default -> {
                if (autoPayActive) {
                    stopAutoPay(false);
                }
                autoPayFailed = false;
                return false;
            }
        }
    }

    private void stopAutoPay(boolean failed) {
        autoPayActive = false;
        autoPayColor = null;
        if (failed) {
            autoPayFailed = true;
        }
    }


    private void onUpdate() {
        if (!passedSigs.isEmpty() && game.getStack().isEmpty()) {
            passedSigs.clear();
        }
        long now = System.currentTimeMillis();
        if (now - lastStateAt >= STATE_MIN_INTERVAL_MS) {
            sendState(false);
        } else {
            stateDirty = true;
        }
    }

    private void flushStateIfDirty() {
        if (stateDirty && ThreadUtils.isRunGameThread()) {
            sendState(false);
        }
    }

    private StateDto sendState(boolean withPlayable) {
        return sendState(withPlayable, null);
    }

    private StateDto sendState(boolean withPlayable, GameViewMapper.Playable playable) {
        trackEliminations();
        StateDto s = GameViewMapper.map(game, humanId, stateSeq.incrementAndGet(), withPlayable, playable, thinking, deckNames);
        lastState = s;
        lastStateAt = System.currentTimeMillis();
        stateDirty = false;
        emit(s);
        return s;
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

    private void emit(Object msg) {
        if (msg instanceof StateDto || msg instanceof PromptDto || msg instanceof Messages.Log) {
            lastProgressAt = System.currentTimeMillis();
        }
        try {
            sink.send(msg);
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
            if (++ticks % 2 == 0) {
                emit(activity(t, waiting, now));
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
        Response r = lastApplied;
        if (t == null || target == null || r == null || recoverAttempts >= RECOVER_MAX_PER_ANSWER
                || !looksLost(System.currentTimeMillis()) || !inWaitForResponse(t)) {
            return;
        }
        recoverAttempts++;
        recovered++;
        LOG.warn("Antwort ging verloren (XMage-Race) - stelle erneut zu: " + r);
        setResponse(target, r);
        lastAppliedAt = System.currentTimeMillis();
    }

    private Messages.Activity activity(Thread t, boolean waiting, long now) {
        int cpu = cpuPercent(now);
        long idle = now - lastProgressAt;
        UUID th = thinking;
        String mode;
        String who = null;
        if (waiting && openPrompt.get() != null) {
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
        return new Messages.Activity(mode, who, cpu, idle, recovered);
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
                    p.getId().equals(humanId), p.getLife(), eliminatedTurn.get(p.getId()),
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

    public boolean isHumanConceded() {
        return humanConceded;
    }

    public void setRewardHook(java.util.function.BiFunction<GameHost, Messages.GameOver, Object> hook) {
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
