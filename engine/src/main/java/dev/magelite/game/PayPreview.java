package dev.magelite.game;

import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Vorschau fuer "Automatisch bezahlen": welche Manaquellen Forges Auto-Bezahlen fuer eine Karte tappen wuerde.
 * Gleicher Testpfad wie Forges eigene Vorschau im Bezahl-Input ({@code InputPayMana.ensureAutoPayManaSources}):
 * {@link ComputerUtilMana#getManaSourcesToPayCost} unter einem KI-Controller, nichts wird getappt oder bezahlt.
 * Nur Spiel-Thread.
 */
final class PayPreview {

    private static final Logger LOG = Logger.getLogger(PayPreview.class);

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

    /**
     * Groesstes X, das p fuer sa gerade bezahlen koennte (Mana-X; Forges KI-Rechnung
     * {@link ComputerUtilMana#determineLeftoverMana}: X = 1..99 per Testzahlung, mit Kostenanpassung, XX und Manapool,
     * nichts wird getappt). Quellen, die Forges KI nicht nutzt, zaehlen nicht. -1: nicht bestimmbar.
     */
    static int maxX(Player p, SpellAbility sa) {
        int[] out = {-1};
        try {
            p.runWithController(() -> out[0] = ComputerUtilMana.determineLeftoverMana(sa, p, false),
                    new PlayerControllerAi(p.getGame(), p, p.getOriginalLobbyPlayer()));
        } catch (RuntimeException e) {
            LOG.warn("X-Maximum fuer " + sa.getHostCard() + " nicht bestimmbar: " + e);
            return -1;
        }
        return out[0];
    }
}
