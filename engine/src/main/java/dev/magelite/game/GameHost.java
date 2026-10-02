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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    private final AtomicLong stateSeq = new AtomicLong();
    private final AtomicLong promptSeq = new AtomicLong();
    private final Deque<Messages.LogEntry> logTail = new ArrayDeque<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, Integer> eliminatedTurn = new LinkedHashMap<>();

    private volatile Sink sink = msg -> {
    };
    private volatile StateDto lastState;
    private volatile PromptDto openPrompt;
    private volatile UUID thinking;
    private volatile boolean autoPass = true;
    private volatile Messages.GameOver gameOver;
    private volatile Consumer<GameHost> onFinished;
    private volatile java.util.function.BiFunction<GameHost, Messages.GameOver, Object> rewardHook;
    private volatile boolean humanConceded;
    // Auto-Bezahlen (Game-Thread)
    private volatile boolean autoPayDefault = true;
    private boolean autoPayActive;
    private boolean autoPayFailed;
    private AutoPayer.Color autoPayColor;
    private int autoPaySteps;
    private String autoPayLastMsg;
    private long lastStateAt;
    private boolean stateDirty;
    private Thread gameThread;
    private long startedAt;

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
        PromptDto p = openPrompt;
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
        }
    }

    /**
     * Beendet das Spiel: alle Spieler geben auf (Interrupts schluckt XMage).
     */
    public void abort() {
        humanConceded = true;
        callExecutor.execute(() -> {
            for (Player p : game.getPlayers().values()) {
                if (p.isInGame()) {
                    game.setConcedingPlayer(p.getId());
                }
            }
        });
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
        PromptDto p = openPrompt;
        if (p == null || p.id != promptId) {
            return false;
        }
        openPrompt = null;
        emit(new Messages.PromptClosed(promptId));
        dispatch(target -> {
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
        });
        return true;
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
        boolean closesPrompt = action.name().startsWith("PASS_PRIORITY_UNTIL") || action == PlayerAction.CONCEDE;
        PromptDto p = openPrompt;
        if (closesPrompt && p != null && "SELECT".equals(p.kind)) {
            openPrompt = null;
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
            thinking = null;
            PromptDto prompt = PromptMapper.map(game, event, humanId);
            if (prompt == null) {
                return;
            }

            if (handleAutoPay(prompt)) {
                return;
            }

            boolean priorityPrompt = "SELECT".equals(prompt.kind) && "priority".equals(prompt.mode);
            StateDto state = sendState(true);

            if (autoPass && priorityPrompt && state.actions != null && state.actions.isEmpty()) {
                // nichts spielbar (ausser Mana) -> automatisch passen
                prompt.id = promptSeq.incrementAndGet();
                openPrompt = prompt;
                respondInternal(prompt.id, Response.ofBool(false));
                return;
            }

            prompt.id = promptSeq.incrementAndGet();
            prompt.stateSeq = state.seq;
            openPrompt = prompt;
            emit(prompt);
        } catch (Throwable e) {
            LOG.error("QueryEvent " + event.getQueryType() + " fehlgeschlagen", e);
        }
    }

    /** Auto-Antwort ohne promptClosed-Nachricht an den Client. */
    private void respondInternal(long promptId, Response r) {
        PromptDto p = openPrompt;
        if (p == null || p.id != promptId) {
            return;
        }
        openPrompt = null;
        dispatch(target -> apply(target, r));
    }

    private void apply(Player target, Response r) {
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
        openPrompt = prompt;
        respondInternal(prompt.id, r);
    }

    // ------------------------------------------------------------------ Auto-Bezahlen

    public void setAutoPayDefault(boolean on) {
        this.autoPayDefault = on;
    }

    /** Client-Knopf "Auto bezahlen" bei offenem Mana-Prompt (Game-Thread wartet gerade). */
    public void autoPayNow() {
        PromptDto p = openPrompt;
        if (p == null || !"PLAY_MANA".equals(p.kind)) {
            return;
        }
        callExecutor.execute(() -> {
            AutoPayer.Step st = AutoPayer.next(game, humanId, p.messageText, GameViewMapper.playable(game, human).all().keySet());
            if (st == null) {
                emit(new Messages.Toast("info", RichText.parse("Automatisches Bezahlen nicht möglich – bitte Manaquellen anklicken.")));
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
        PromptDto p = openPrompt;
        if (p == null || p.id != promptId) {
            return false;
        }
        openPrompt = null;
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
        trackEliminations();
        StateDto s = GameViewMapper.map(game, humanId, stateSeq.incrementAndGet(), withPlayable, thinking, deckNames);
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
        Messages.LogEntry entry = new Messages.LogEntry(System.currentTimeMillis(), game.getTurnNum(), kind, RichText.parse(html));
        synchronized (logTail) {
            logTail.addLast(entry);
            while (logTail.size() > LOG_KEEP) {
                logTail.removeFirst();
            }
        }
        emit(new Messages.Log(List.of(entry)));
    }

    private void emit(Object msg) {
        try {
            sink.send(msg);
        } catch (Throwable e) {
            LOG.warn("Senden fehlgeschlagen: " + e);
        }
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
