package dev.magelite.game;

import dev.magelite.view.ForgeViewMapper;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.Set;

/**
 * Wann ein menschlicher Sitz automatisch passt (Forges eigene Auto-Pass-/Yield-Logik bleibt aus). POC-Umfang: Passen
 * ohne Nicht-Mana-Aktion, nie in den eigenen Hauptphasen bei leerem Stapel. Stopps, F-Tasten, StackSig, nextStop und
 * stopReason folgen in Phase 1.
 */
final class AutoPassPolicy {

    AutoPassPolicy() {
    }

    /** Spiel-Thread, am Prioritaets-Tor. Berechnet nebenbei die Aktionen fuer den naechsten State. */
    boolean autoPass(GameHost.HumanSeat seat, HumanController c) {
        Player me = c.getPlayer();
        Set<CardView> actions = ForgeViewMapper.actionable(me, budgetMs(me));
        seat.cachedActions = actions;
        if (!seat.autoPass()) {
            return false;
        }
        Game game = c.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        PhaseType phase = ph.getPhase();
        boolean ownMain = ph.getPlayerTurn() == me && game.getStack().isEmpty()
                && (phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2);
        return !ownMain && actions.isEmpty();
    }

    /** Wie Forge: 50 ms je Karte in Hand/Spielfeld, 50..1500 ms. */
    static long budgetMs(Player p) {
        int n = p.getCardsIn(ZoneType.Hand).size() + p.getCardsIn(ZoneType.Battlefield).size();
        return Math.min(1500L, Math.max(50L, 50L * n));
    }
}
