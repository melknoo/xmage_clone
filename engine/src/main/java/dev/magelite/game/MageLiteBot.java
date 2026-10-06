package dev.magelite.game;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.mana.ManaAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayerControllableProxy;
import mage.player.ai.SimulationNode2;
import mage.player.ai.score.GameStateEvaluator2;
import mage.player.ai.util.CombatInfo;
import mage.player.ai.util.CombatUtil;
import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * XMage-"mad"-Bot mit einstellbarem Tempo.
 * <p>
 * Groesster Speed-Gewinn: {@code fastOpponentTurns}. Der Original-Bot ({@code ComputerPlayer7.priorityPlay})
 * rechnet in Main/Kampf-Schritten JEDES Spielers eine Minimax-Suche; bei 4 Spielern sind das bis zu
 * 3 Suchen pro Prioritaetsrunde. Mit fastOpponentTurns passt der Bot in fremden Zuegen, solange der
 * Stack leer ist (auf Zauber im Stack reagiert er weiterhin, Blocken laeuft separat ueber selectBlockers).
 * <p>
 * Zweiter grosser Gewinn: {@code fastStack} (Blitz/Normal). Nach JEDEM aufgeloesten Stapelobjekt bekommt jeder Bot
 * wieder Prioritaet, und die Suche simuliert den ganzen Stapel - bei 100 Landfall-Triggern lief sie jedes Mal ins
 * Zeitlimit (3 Bots x Denkzeit pro Trigger). Darum: ohne Nicht-Mana-Aktion sofort passen (Ergebnis identisch), und
 * auf ein Stapelobjekt, auf das der Bot schon gepasst hat, gleich wieder passen ({@link StackSig}).
 */
public class MageLiteBot extends ComputerPlayerControllableProxy {

    private static final Logger LOG = Logger.getLogger(MageLiteBot.class);

    private final TempoSettings tempo;
    private transient BotHooks hooks;
    /** Sperre gegen erneuten Eintritt in selectBlockers (siehe chooseBlockersByEffect). */
    private transient boolean selectingBlockers;
    /** Signaturen der Stapelobjekte, auf die schon gepasst wurde; gilt fuer {@link #passedSigsKey} (Zug/Schritt) */
    private transient Set<String> passedSigs;
    private transient String passedSigsKey;
    /** passt gerade ohne nachzudenken (GameHost zeigt dann kein "denkt ...") - nur auf dem Spiel-Thread gelesen */
    private transient boolean quickPassing;
    /** in diesem priority()-Aufruf wirklich etwas getan (Nicht-Mana-Faehigkeit, Zauber, Land) */
    private transient boolean acted;
    /** Suche abbrechen (statt Thread.interrupt, siehe addActionsTimed) */
    private transient volatile boolean simStop;

    public MageLiteBot(String name, RangeOfInfluence range, TempoSettings tempo) {
        super(name, range, tempo.preset().skill);
        this.tempo = tempo;
        setMaxThinkTimeSecs(tempo.thinkSecs());
    }

    public MageLiteBot(final MageLiteBot other) {
        super(other);
        this.tempo = other.tempo;
        this.hooks = other.hooks;
    }

    @Override
    public MageLiteBot copy() {
        return new MageLiteBot(this);
    }

    public TempoSettings getTempo() {
        return tempo;
    }

    public void setHooks(BotHooks hooks) {
        this.hooks = hooks;
    }

    @Override
    public boolean priority(Game game) {
        if (game.isSimulation()) {
            return super.priority(game);
        }
        setMaxThinkTimeSecs(tempo.thinkSecs());

        if (tempo.fastOpponentTurns()
                && isGameUnderControl()
                && !getId().equals(game.getActivePlayerId())
                && game.getStack().isEmpty()
                && !reactInCombat(game)) {
            actionCache.clear();
            return quickPass(game);
        }

        String sig = null;
        if (tempo.fastStack() && isGameUnderControl()) {
            Set<String> passed = passedSigs(game);
            sig = StackSig.top(game);
            if (sig != null && passed.contains(sig)) {
                return quickPass(game);
            }
            if (!hasNonManaAction(game)) {
                if (sig != null) {
                    passed.add(sig);
                }
                return quickPass(game);
            }
        }

        if (isGameUnderControl() && !SimPool.awaitIdle(tempo.thinkSecs() * 1000L)) {
            LOG.warn(getName() + ": KI-Simulation vom letzten Timeout laeuft noch - passe ohne Suche");
            return quickPass(game);
        }

        acted = false;
        boolean result = super.priority(game);
        if (acted) {
            afterAction(game, tempo.actionDelayMs());
        } else if (sig != null) {
            passedSigs(game).add(sig);
        }
        return result;
    }

    /**
     * Wie {@code ComputerPlayer6.addActionsTimed}, aber ohne {@code task.cancel(true)}: XMage unterbricht den
     * Simulations-Thread per {@code Thread.interrupt()}. Steckt der gerade in einem H2-Lesezugriff (z.B.
     * {@code CardRepository.getNames()} fuer Demonic Consultation), schliesst Java den Dateikanal und die Karten-DB
     * ist fuer den ganzen Prozess kaputt. Hier stattdessen kooperativer Stopp ueber {@link #simStop}, den
     * {@link #addActions} an denselben Stellen prueft wie XMage den Interrupt.
     */
    @Override
    protected Integer addActionsTimed() {
        ThreadPoolExecutor pool = SimPool.POOL;
        if (pool == null) {
            return super.addActionsTimed();
        }
        simStop = false;
        FutureTask<Integer> task = new FutureTask<>(() -> addActions(root, maxDepth, Integer.MIN_VALUE, Integer.MAX_VALUE));
        pool.execute(task);
        try {
            return task.get(maxThinkTimeSecs, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            LOG.warn("AI player thinks too long: " + getName() + " - battlefield size: "
                    + root.getGame().getBattlefield().getAllPermanents().size() + ", stack: " + root.getGame().getStack());
            simStop = true;
            task.cancel(false);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } catch (ExecutionException e) {
            LOG.error("AI player catch game error in simulation - " + getName() + ": " + e.getCause(), e.getCause());
            simStop = true;
            task.cancel(false);
        } catch (RuntimeException e) {
            LOG.error("AI player catch unknown error in simulation - " + getName() + ": " + e, e);
            simStop = true;
            task.cancel(false);
        }
        return 0;
    }

    @Override
    protected int addActions(SimulationNode2 node, int depth, int alpha, int beta) {
        if (simStop) {
            // gleiche Semantik wie XMages Interrupt-Check am Anfang von addActions
            return GameStateEvaluator2.evaluate(playerId, node.getGame()).getTotalScore();
        }
        return super.addActions(node, depth, alpha, beta);
    }

    /** Passt ohne Suche (wie ComputerPlayer7 bei UPKEEP/DRAW). */
    private boolean quickPass(Game game) {
        quickPassing = true;
        try {
            game.getState().setPriorityPlayerId(getId());
            game.firePriorityEvent(getId());
            pass(game);
        } finally {
            quickPassing = false;
        }
        return false;
    }

    /**
     * Fremder Kampf (Angreifer/Blocker erklaert) und wir haben eine Spontanaktion -> trotz fastOpponentTurns normal
     * rechnen (Kampftricks, Removal auf Angreifer). Kostet nur Zeit, wenn wirklich etwas spielbar ist.
     */
    private boolean reactInCombat(Game game) {
        if (!tempo.reactInCombat() || !BotTuning.enabled(getId(), BotTuning.Lever.REACT_IN_COMBAT)) {
            return false;
        }
        PhaseStep step = game.getTurnStepType();
        if (step != PhaseStep.DECLARE_ATTACKERS && step != PhaseStep.DECLARE_BLOCKERS) {
            return false;
        }
        return hasNonManaAction(game);
    }

    /** Merkliste fuer den aktuellen Stapel; leer, sobald der Stapel leer ist oder Zug/Schritt wechselt. */
    private Set<String> passedSigs(Game game) {
        String key = game.getTurnNum() + "/" + game.getTurnStepType();
        if (passedSigs == null) {
            passedSigs = new HashSet<>();
        }
        if (game.getStack().isEmpty() || !key.equals(passedSigsKey)) {
            passedSigs.clear();
            passedSigsKey = key;
        }
        return passedSigs;
    }

    /** Kann der Bot gerade irgendetwas ausser Mana-Faehigkeiten tun? Im Zweifel ja (dann wird normal gerechnet). */
    private boolean hasNonManaAction(Game game) {
        try {
            for (ActivatedAbility a : getPlayable(game, true)) {
                if (!(a instanceof ManaAbility)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            LOG.warn(getName() + ": Spielbarkeit nicht pruefbar - rechne normal: " + e);
            return true;
        }
    }

    public boolean isQuickPassing() {
        return quickPassing;
    }

    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        boolean ok = super.activateAbility(ability, game);
        if (ok && !game.isSimulation() && !(ability instanceof ManaAbility)) {
            acted = true;
        }
        return ok;
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if (!game.isSimulation() && isGameUnderControl() && BotTuning.enabled(getId(), BotTuning.Lever.FFA_ATTACK)) {
            FfaAttack.declareAttackers(this, game);
        } else {
            super.selectAttackers(game, attackingPlayerId);
        }
        if (!game.isSimulation()) {
            afterAction(game, tempo.combatDelayMs());
        }
    }

    /**
     * {@code source == null}: normales Blocken als Verteidiger (XMage-KI).
     * {@code source != null}: ein Effekt laesst uns die Blocker bestimmen (z.B. Odric, Master Warcraft) -> eigene Logik,
     * weil die XMage-KI das nicht kann (siehe {@link #chooseBlockersByEffect}).
     */
    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        if (selectingBlockers) {
            // Sicherheitsnetz: erneuter Aufruf aus dem eigenen Blocken heraus -> nichts tun statt Endlosrekursion
            LOG.warn(getName() + ": selectBlockers erneut aufgerufen - ignoriert");
            return;
        }
        selectingBlockers = true;
        try {
            if (source == null) {
                super.selectBlockers(null, game, defendingPlayerId);
            } else {
                chooseBlockersByEffect(source, game, defendingPlayerId);
            }
        } finally {
            selectingBlockers = false;
        }
        if (!game.isSimulation()) {
            afterAction(game, tempo.combatDelayMs());
        }
    }

    /**
     * Wir bestimmen per Effekt ("you choose which creatures block", {@code ChooseBlockersEffect}) die Blocker von
     * {@code defenderId}. {@code ComputerPlayer6.selectBlockers} ignoriert Quelle und Verteidiger, blockt mit eigenen
     * Kreaturen und feuert {@code DECLARING_BLOCKERS} erneut - der Effekt ruft dann wieder uns auf -> StackOverflowError.
     * <ul>
     *   <li>Wir selbst verteidigen: gute Tauschgeschaefte wie {@code ComputerPlayer6.declareBlockers}, nur ohne Ereignis.</li>
     *   <li>Fremder Verteidiger: keine Blocker - dessen Kreaturen schuetzen ihn nicht, unsere Angreifer kommen durch.</li>
     * </ul>
     */
    private void chooseBlockersByEffect(Ability source, Game game, UUID defenderId) {
        if (!getId().equals(defenderId)) {
            if (!game.isSimulation()) {
                LOG.info(getName() + ": bestimmt Blocker von " + playerName(game, defenderId) + " per Effekt ("
                        + sourceName(source, game) + ") - keine Blocker");
            }
            return;
        }
        List<Permanent> attackers = new ArrayList<>();
        for (UUID attackerId : game.getCombat().getAttackers()) {
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker != null && defenderId.equals(game.getCombat().getDefendingPlayerId(attackerId, game))) {
                attackers.add(attacker);
            }
        }
        List<Permanent> blockers = new ArrayList<>();
        for (Permanent blocker : getAvailableBlockers(game)) {
            if (attackers.stream().anyMatch(a -> blocker.canBlock(a.getId(), game))) {
                blockers.add(blocker);
            }
        }
        attackers.removeIf(a -> !CombatUtil.canBeBlocked(game, a, blockers));
        int declared = 0;
        if (!attackers.isEmpty() && !blockers.isEmpty()) {
            CombatUtil.sortByPower(attackers, false);
            CombatInfo info = CombatUtil.blockWithGoodTrade2(game, attackers, blockers);
            for (Map.Entry<Permanent, List<Permanent>> e : info.getCombat().entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                for (Permanent blocker : e.getValue()) {
                    declareBlocker(defenderId, blocker.getId(), e.getKey().getId(), game);
                    declared++;
                }
            }
            if (declared > 0) {
                game.getPlayers().resetPassed();
            }
        }
        if (!game.isSimulation()) {
            LOG.info(getName() + ": bestimmt eigene Blocker per Effekt (" + sourceName(source, game) + ") - " + declared + " Blocker");
        }
    }

    private static String playerName(Game game, UUID playerId) {
        var p = game.getPlayer(playerId);
        return p == null ? String.valueOf(playerId) : p.getName();
    }

    private static String sourceName(Ability source, Game game) {
        var obj = source.getSourceObject(game);
        return obj == null ? "?" : obj.getName();
    }

    private void afterAction(Game game, int delayMs) {
        BotHooks h = hooks;
        if (h != null) {
            h.afterBotAction(game, this);
        }
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Simulations-Pool von {@code ComputerPlayer6} (statisch, von allen Bots geteilt). Nach einem Timeout bricht XMage
     * die Suche per Interrupt ab, aber einzelne Schritte (z.B. alle Zielkombinationen einer Opfer-Faehigkeit
     * erzeugen) pruefen den Interrupt nicht und laufen weiter. Startet der naechste Bot trotzdem eine Suche, stapeln
     * sich solche Laeufe, bis der Heap voll ist (Arena 2026-10-05: OutOfMemoryError). Darum: vor jeder Suche warten,
     * bis der Pool leer ist.
     */
    static final class SimPool {
        private static final ThreadPoolExecutor POOL = find();

        private SimPool() {
        }

        private static ThreadPoolExecutor find() {
            try {
                Field f = mage.player.ai.ComputerPlayer6.class.getDeclaredField("threadPoolSimulations");
                f.setAccessible(true);
                return f.get(null) instanceof ThreadPoolExecutor tpe ? tpe : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.warn("KI-Simulations-Pool nicht gefunden - keine Sperre gegen gestapelte Suchen: " + e);
                return null;
            }
        }

        /** true, wenn der Pool (spaetestens nach {@code maxWaitMs}) keine laufende Simulation mehr hat. */
        static boolean awaitIdle(long maxWaitMs) {
            if (POOL == null || POOL.getActiveCount() == 0) {
                return true;
            }
            long end = System.currentTimeMillis() + maxWaitMs;
            try {
                while (POOL.getActiveCount() > 0 && System.currentTimeMillis() < end) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return POOL.getActiveCount() == 0;
        }
    }

    /**
     * Callback nach sichtbaren Bot-Aktionen (auf dem Game-Thread), z.B. um einen Snapshot an die UI zu schicken.
     */
    public interface BotHooks {
        void afterBotAction(Game game, MageLiteBot bot);
    }
}
