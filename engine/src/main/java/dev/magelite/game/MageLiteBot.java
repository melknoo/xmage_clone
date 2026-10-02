package dev.magelite.game;

import mage.abilities.Ability;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.ComputerPlayerControllableProxy;

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

    private final TempoSettings tempo;
    private transient BotHooks hooks;

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

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        super.selectBlockers(source, game, defendingPlayerId);
        if (!game.isSimulation()) {
            afterAction(game, tempo.combatDelayMs());
        }
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
