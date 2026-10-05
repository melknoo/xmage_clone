package dev.magelite.spike;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.players.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Vorbereitete Spielsituationen fuer Tests (HumanSpike, Dev-Engine). Nur vor {@code game.start()} anwenden.
 * Kein Spieler-Werkzeug: die Dev-Engine nimmt Szenarien nur im {@code --dev}-Modus an.
 */
public final class Scenarios {

    private Scenarios() {
    }

    public static boolean exists(String name) {
        return name != null && List.of("swarm", "dredge").contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * {@code swarm}: lange Trigger-Ketten. Ich beginne mit 6 Waeldern + 16 Scute Swarm, auf der Hand 2 Waelder und
     * Giant Growth (damit ich etwas Spielbares habe). Jeder Bot: 2 Berge + Mogg Fanatic (immer aktivierbar),
     * Lightning Bolt auf der Hand - Bots haben also Antworten und muessen ueber Trigger "nachdenken".
     */
    public static void apply(String name, Game game, UUID humanId) {
        if (!exists(name)) {
            throw new IllegalArgumentException("Unbekanntes Szenario: " + name);
        }
        if ("dredge".equals(name.toLowerCase(Locale.ROOT))) {
            dredge(game, humanId);
            return;
        }
        List<PutToBattlefieldInfo> mine = new ArrayList<>();
        mine.addAll(battlefield("Forest", 6));
        mine.addAll(battlefield("Scute Swarm", 16));
        cheat(game, humanId, cards("Forest", "Forest", "Giant Growth"), mine, List.of());
        for (Player p : game.getPlayers().values()) {
            if (p.getId().equals(humanId)) {
                continue;
            }
            List<PutToBattlefieldInfo> bf = new ArrayList<>(battlefield("Mountain", 2));
            bf.addAll(battlefield("Mogg Fanatic", 1));
            cheat(game, p.getId(), cards("Lightning Bolt"), bf, List.of());
        }
        game.setStartingPlayerId(humanId);
    }

    /**
     * {@code dredge}: Ersatzeffekt-Wahl beim Ziehen. Mein Friedhof: 5x Dredge 2 (gleicher Regeltext -> eine
     * Gruppe), Life from the Loam (Dredge 3) und Stinkweed Imp (Dredge 5) -> 3 Gruppen. 4 Waelder im Spiel.
     * Ein Bot beginnt, damit ich schon im ersten eigenen Zug ziehe.
     */
    private static void dredge(Game game, UUID humanId) {
        List<Card> gy = cards("Dakmor Salvage", "Golgari Brownscale", "Moldervine Cloak", "Necroplasm", "Nightmare Void",
                "Life from the Loam", "Stinkweed Imp");
        cheat(game, humanId, List.of(), battlefield("Forest", 4), gy);
        UUID starter = null;
        for (Player p : game.getPlayers().values()) {
            if (!p.getId().equals(humanId) && starter == null) {
                starter = p.getId();
            }
        }
        if (starter != null) {
            game.setStartingPlayerId(starter);
        }
    }

    /**
     * {@code game.cheat} legt die Karten ab und wendet danach die Effekte an. Vor {@code game.start()} wirft das bei
     * manchen Karten im Spiel (z.B. P/T = Karten in allen Friedhoefen: "game is not started"); die Karten liegen dann
     * schon richtig, die Effekte rechnet der Spielstart neu.
     */
    private static void cheat(Game game, UUID playerId, List<Card> hand, List<PutToBattlefieldInfo> battlefield, List<Card> graveyard) {
        try {
            game.cheat(playerId, List.of(), hand, battlefield, graveyard, List.of(), List.of());
        } catch (IllegalStateException e) {
            if (e.getMessage() == null || !e.getMessage().contains("game is not started")) {
                throw e;
            }
        }
    }

    private static List<PutToBattlefieldInfo> battlefield(String name, int n) {
        List<PutToBattlefieldInfo> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new PutToBattlefieldInfo(card(name), false));
        }
        return out;
    }

    private static List<Card> cards(String... names) {
        List<Card> out = new ArrayList<>();
        for (String n : names) {
            out.add(card(n));
        }
        return out;
    }

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findPreferredCoreExpansionCard(name);
        if (info == null) {
            throw new IllegalStateException("Karte nicht gefunden: " + name);
        }
        return info.createCard();
    }
}
