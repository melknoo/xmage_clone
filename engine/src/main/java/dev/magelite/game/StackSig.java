package dev.magelite.game;

import mage.MageObject;
import mage.game.Game;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.target.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * "Gleiche" Stapelobjekte erkennen, z.B. 112 Landfall-Trigger von Scute-Swarm-Kopien. Wer auf eines davon gepasst
 * hat, passt auf die anderen auch (Bots in Blitz/Normal, der Mensch immer).
 */
public final class StackSig {

    private StackSig() {
    }

    /**
     * Signatur des obersten Stapelobjekts: Controller, Quellname, Regeltext, Ziele. Die Quell-ID gehoert bewusst nicht
     * dazu (jede Kopie ist eine andere Quelle). Nur fuer Faehigkeiten - Zauber bekommen {@code null} und werden immer
     * einzeln entschieden.
     */
    public static String top(Game game) {
        StackObject so = game.getStack().getFirstOrNull();
        if (!(so instanceof StackAbility sa)) {
            return null;
        }
        try {
            MageObject src = sa.getSourceObject(game);
            List<String> targets = new ArrayList<>();
            for (Target t : sa.getAllSelectedTargets()) {
                for (UUID id : t.getTargets()) {
                    targets.add(id.toString());
                }
            }
            Collections.sort(targets);
            return sa.getControllerId() + "|" + (src == null ? "" : src.getName()) + "|" + sa.getRule() + "|" + String.join(",", targets);
        } catch (RuntimeException e) {
            return null; // im Zweifel einzeln entscheiden
        }
    }
}
