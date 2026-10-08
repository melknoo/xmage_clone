package dev.magelite.game;

import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stapelobjekte fremder Spieler, die einen Spieler oder etwas von ihm als Ziel haben (der Spieler selbst, seine
 * bleibenden Karten, seine Stapelobjekte, seine Karten in anderen Zonen). Grundlage fuer "Auto-Passen anhalten,
 * sobald ich anvisiert werde".
 */
public final class TargetCheck {

    private TargetCheck() {
    }

    /** Ein anvisierendes Stapelobjekt: seine ID, Quellname und das getroffene Ziel (Name). */
    public record Hit(UUID stackId, String source, String target) {
    }

    public static List<Hit> targeting(Game game, UUID playerId) {
        List<Hit> hits = new ArrayList<>();
        try {
            for (StackObject so : game.getStack()) {
                if (playerId.equals(so.getControllerId()) || so.getStackAbility() == null) {
                    continue;
                }
                String hit = null;
                for (Target t : so.getStackAbility().getAllSelectedTargets()) {
                    for (UUID id : t.getTargets()) {
                        hit = mine(game, playerId, id);
                        if (hit != null) {
                            break;
                        }
                    }
                    if (hit != null) {
                        break;
                    }
                }
                if (hit != null) {
                    hits.add(new Hit(so.getId(), so.getName(), hit));
                }
            }
        } catch (RuntimeException e) {
            // Stapel aendert sich gerade o.ae. -> lieber nichts melden
        }
        return hits;
    }

    /** Name des Ziels, falls es dem Spieler gehoert/von ihm kontrolliert wird; sonst null. */
    private static String mine(Game game, UUID playerId, UUID id) {
        if (playerId.equals(id)) {
            return "dich";
        }
        Permanent perm = game.getPermanent(id);
        if (perm != null) {
            return playerId.equals(perm.getControllerId()) ? perm.getName() : null;
        }
        StackObject so = game.getStack().getStackObject(id);
        if (so != null) {
            return playerId.equals(so.getControllerId()) ? so.getName() : null;
        }
        var card = game.getCard(id);
        if (card != null && playerId.equals(card.getOwnerId())) {
            return card.getName();
        }
        return null;
    }
}
