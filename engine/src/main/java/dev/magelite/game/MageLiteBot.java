package dev.magelite.game;

import mage.abilities.Ability;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayerControllableProxy;
import mage.player.ai.util.CombatInfo;
import mage.player.ai.util.CombatUtil;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * XMage-"mad"-Bot mit einstellbarem Tempo.
 * <p>
 * Groesster Speed-Gewinn: {@code fastOpponentTurns}. Der Original-Bot ({@code ComputerPlayer7.priorityPlay})
 * rechnet in Main/Kampf-Schritten JEDES Spielers eine Minimax-Suche; bei 4 Spielern sind das bis zu
 * 3 Suchen pro Prioritaetsrunde. Mit fastOpponentTurns passt der Bot in fremden Zuegen, solange der
 * Stack leer ist (auf Zauber im Stack reagiert er weiterhin, Blocken laeuft separat ueber selectBlockers).
 */
public class MageLiteBot extends ComputerPlayerControllableProxy {

    private static final Logger LOG = Logger.getLogger(MageLiteBot.class);

    private final TempoSettings tempo;
    private transient BotHooks hooks;
    /** Sperre gegen erneuten Eintritt in selectBlockers (siehe chooseBlockersByEffect). */
    private transient boolean selectingBlockers;

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
                && game.getStack().isEmpty()) {
            game.getState().setPriorityPlayerId(getId());
            game.firePriorityEvent(getId());
            actionCache.clear();
            pass(game);
            return false;
        }

        boolean acted = super.priority(game);
        if (acted) {
            afterAction(game, tempo.actionDelayMs());
        }
        return acted;
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        super.selectAttackers(game, attackingPlayerId);
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
     * Callback nach sichtbaren Bot-Aktionen (auf dem Game-Thread), z.B. um einen Snapshot an die UI zu schicken.
     */
    public interface BotHooks {
        void afterBotAction(Game game, MageLiteBot bot);
    }
}
