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
        return name != null && List.of("swarm", "dredge", "gemstone", "necro", "convoke", "blocker").contains(name.toLowerCase(Locale.ROOT));
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
        if ("gemstone".equals(name.toLowerCase(Locale.ROOT))) {
            gemstone(game, humanId);
            return;
        }
        if ("convoke".equals(name.toLowerCase(Locale.ROOT))) {
            convoke(game, humanId);
            return;
        }
        if ("blocker".equals(name.toLowerCase(Locale.ROOT))) {
            blocker(game, humanId);
            return;
        }
        if ("necro".equals(name.toLowerCase(Locale.ROOT))) {
            cheat(game, humanId, List.of(), battlefield("Necropotence", 1), List.of());
            game.setStartingPlayerId(humanId);
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
     * {@code gemstone}: Starthand-Aktion vor dem ersten Zug. Gemstone Caverns auf der Hand, ein Bot beginnt (nur dann
     * darf sie ins Spiel): XMage fragt "Put Gemstone Caverns onto the battlefield?", danach "exile a card from hand".
     */
    private static void gemstone(Game game, UUID humanId) {
        cheat(game, humanId, cards("Gemstone Caverns"), List.of(), List.of());
        botStarts(game, humanId);
    }

    /**
     * {@code convoke}: X-Zauber und Einberufen. Ich beginne mit 3 Bergen, 2 Suempfen, Watchwolf (gruen-weiss),
     * 3 Grizzly Bears und 2 Savannah Lions; auf der Hand Blaze ({X}{R}) und Guardian of Vitu-Ghazi ({6}{G}{W},
     * Convoke). Keine gruenen/weissen Laender: G und W muessen die Kreaturen zahlen, Watchwolf bekommt die Farbwahl.
     */
    private static void convoke(Game game, UUID humanId) {
        List<PutToBattlefieldInfo> mine = new ArrayList<>();
        mine.addAll(battlefield("Mountain", 3));
        mine.addAll(battlefield("Swamp", 2));
        mine.addAll(battlefield("Watchwolf", 1));
        mine.addAll(battlefield("Grizzly Bears", 3));
        mine.addAll(battlefield("Savannah Lions", 2));
        cheat(game, humanId, cards("Blaze", "Guardian of Vitu-Ghazi"), mine, List.of());
        game.setStartingPlayerId(humanId);
    }

    /**
     * {@code blocker}: Blocker-Wahl im ersten Bot-Zug. Jeder Bot: 3 Berge, Craw Wurm (6/4) und Hill Giant (3/3) -
     * kampfbereit, weil sie schon vor seinem ersten Zug liegen. Ich: 3 Waelder, 2 Grizzly Bears, Llanowar Elves.
     * Ein Bot beginnt.
     */
    private static void blocker(Game game, UUID humanId) {
        List<PutToBattlefieldInfo> mine = new ArrayList<>(battlefield("Forest", 3));
        mine.addAll(battlefield("Grizzly Bears", 2));
        mine.addAll(battlefield("Llanowar Elves", 1));
        cheat(game, humanId, List.of(), mine, List.of());
        for (Player p : game.getPlayers().values()) {
            if (p.getId().equals(humanId)) {
                continue;
            }
            List<PutToBattlefieldInfo> bf = new ArrayList<>(battlefield("Mountain", 3));
            bf.addAll(battlefield("Craw Wurm", 1));
            bf.addAll(battlefield("Hill Giant", 1));
            cheat(game, p.getId(), List.of(), bf, List.of());
        }
        botStarts(game, humanId);
    }

    private static void botStarts(Game game, UUID humanId) {
        for (Player p : game.getPlayers().values()) {
            if (!p.getId().equals(humanId)) {
                game.setStartingPlayerId(p.getId());
                return;
            }
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
