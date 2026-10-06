package dev.magelite.game;

import mage.constants.PhaseStep;
import mage.game.Game;
import mage.players.Player;

/**
 * Wohin "Weiter" im eigenen Zug (leerer Stapel) fuehrt - fuer die Beschriftung des Knopfs. Folgt den Stopps aus
 * {@link HumanSettings} (eigene Main-Phasen halten immer, Kampf nur mit moeglichen Angreifern) und dem Auto-Passen
 * in {@code GameHost}. Unscharf nur bei Auto-Passen mit spielbaren Spontanzaubern: dann kommt im Kampf noch ein Stopp.
 */
final class NextStop {

    private NextStop() {
    }

    /** @return main1 | combat | main2 | end, oder null (unbekannt -> "Weiter") */
    static String of(Game game, Player me, boolean autoPass) {
        PhaseStep step = game.getTurnStepType();
        if (step == null) {
            return null;
        }
        return switch (step) {
            case UNTAP, UPKEEP, DRAW -> "main1";
            case PRECOMBAT_MAIN, BEGIN_COMBAT -> !autoPass || hasAttackers(game, me) ? "combat" : "main2";
            case DECLARE_ATTACKERS, DECLARE_BLOCKERS, FIRST_COMBAT_DAMAGE, COMBAT_DAMAGE -> autoPass ? "main2" : null;
            case END_COMBAT -> "main2";
            default -> "end";
        };
    }

    /**
     * Pro Gegner pruefen: {@code getAvailableAttackers(game)} (ohne Verteidiger) fragt XMages Kampf-Verteidiger ab,
     * die erst zu Kampfbeginn gesetzt werden - in Main 1 waere die Liste immer leer.
     */
    private static boolean hasAttackers(Game game, Player me) {
        try {
            for (java.util.UUID opp : game.getOpponents(me.getId())) {
                Player p = game.getPlayer(opp);
                if (p != null && p.isInGame() && !me.getAvailableAttackers(opp, game).isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
