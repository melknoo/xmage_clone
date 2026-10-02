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
        return name != null && "swarm".equals(name.toLowerCase(Locale.ROOT));
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
        List<PutToBattlefieldInfo> mine = new ArrayList<>();
        mine.addAll(battlefield("Forest", 6));
        mine.addAll(battlefield("Scute Swarm", 16));
        game.cheat(humanId, List.of(), cards("Forest", "Forest", "Giant Growth"), mine, List.of(), List.of(), List.of());
        for (Player p : game.getPlayers().values()) {
            if (p.getId().equals(humanId)) {
                continue;
            }
            List<PutToBattlefieldInfo> bf = new ArrayList<>(battlefield("Mountain", 2));
            bf.addAll(battlefield("Mogg Fanatic", 1));
            game.cheat(p.getId(), List.of(), cards("Lightning Bolt"), bf, List.of(), List.of(), List.of());
        }
        game.setStartingPlayerId(humanId);
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
