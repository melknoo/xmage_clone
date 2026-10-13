package dev.magelite.game;

import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.List;

/**
 * Vorschau fuer "Automatisch bezahlen": welche Manaquellen Forges Auto-Bezahlen fuer eine Karte tappen wuerde.
 * Gleicher Testpfad wie Forges eigene Vorschau im Bezahl-Input ({@code InputPayMana.ensureAutoPayManaSources}):
 * {@link ComputerUtilMana#getManaSourcesToPayCost} unter einem KI-Controller, nichts wird getappt oder bezahlt.
 * Nur Spiel-Thread.
 */
final class PayPreview {

    private PayPreview() {
    }

    /**
     * Quellen fuer die erste wirkbare Nicht-Mana-/Nicht-Land-Faehigkeit der Karte (bei mehreren Wirkweisen ungenau),
     * X = 0. Leere Liste: nichts zu bezahlen oder nicht bezahlbar.
     */
    static List<Card> sourcesFor(Player p, Card card) {
        List<Card> out = new ArrayList<>();
        p.runWithController(() -> {
            for (SpellAbility sa : card.getAllPossibleAbilities(p, true)) {
                if (sa.isManaAbility() || sa.isLandAbility()) {
                    continue;
                }
                Cost cost = sa.getPayCosts();
                if (cost == null || !cost.hasManaCost()) {
                    return;
                }
                ManaCostBeingPaid mana = ComputerUtilMana.calculateManaCost(cost.copy(), sa, p, true, 0, false);
                CardCollection src = ComputerUtilMana.getManaSourcesToPayCost(new ManaCostBeingPaid(mana), sa, p, false);
                if (src != null) {
                    out.addAll(src);
                }
                return;
            }
        }, new PlayerControllerAi(p.getGame(), p, p.getOriginalLobbyPlayer()));
        return out;
    }
}
