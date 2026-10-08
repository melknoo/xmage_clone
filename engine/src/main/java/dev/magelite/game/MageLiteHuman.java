package dev.magelite.game;

import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.human.HumanPlayer;

import java.util.function.Predicate;

/**
 * {@link HumanPlayer} mit Haltepunkt fuer laufendes F-Tasten-Passen: XMage passt dabei intern, ohne Prompt. Meldet der
 * {@link #setStopGuard Guard} "anhalten" (z.B. ich werde anvisiert, Gegner-Upkeep), werden die Pass-Flags vor der
 * Prioritaet zurueckgesetzt - direkt auf dem Spiel-Thread, ohne {@code sendPlayerAction}. In KI-Simulationen
 * ({@code game.isSimulation()}) greift der Guard nie.
 */
public class MageLiteHuman extends HumanPlayer {

    private transient Predicate<Game> stopGuard;

    public MageLiteHuman(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    private MageLiteHuman(MageLiteHuman player) {
        super(player);
        this.stopGuard = player.stopGuard;
    }

    @Override
    public MageLiteHuman copy() {
        return new MageLiteHuman(this);
    }

    public void setStopGuard(Predicate<Game> stopGuard) {
        this.stopGuard = stopGuard;
    }

    @Override
    public boolean priority(Game game) {
        Predicate<Game> guard = stopGuard;
        if (guard != null && !game.isSimulation() && passing() && guard.test(game)) {
            resetPlayerPassedActions();
        }
        return super.priority(game);
    }

    private boolean passing() {
        return getPassedAllTurns() || getPassedTurn() || getPassedUntilEndOfTurn() || getPassedUntilNextMain()
                || getPassedUntilStackResolved() || getPassedUntilEndStepBeforeMyTurn();
    }
}
