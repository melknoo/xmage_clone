package dev.magelite.spike;

import dev.magelite.game.GameHost;
import dev.magelite.game.ScenarioHooks;
import forge.StaticData;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Vorbereitete Spielsituationen fuer Tests (HumanSpike, Dev-Engine) als {@link ScenarioHooks}-Fabrik. Kein
 * Spieler-Werkzeug: die Dev-Engine nimmt Szenarien nur im {@code --dev}-Modus an.
 * <p>
 * Aufbau in {@link ScenarioHooks#startGame}: der Hook laeuft in {@code PhaseHandler.setupFirstTurn} (Untap-Schritt von
 * Zug 1 schon durch, nach Mulligans und Starthand-Aktionen). Karten werden aus der Forge-Kartendatenbank erzeugt und
 * ueber {@code GameAction.moveTo} platziert (nicht ueber {@code forge.game.GameState}, das Zonen leert); Kreaturen
 * bekommen keine Einsatz-Krankheit. Die Karten kommen zusaetzlich zu Starthand und Bibliothek ins Spiel (nichts wird
 * entfernt) und gehoeren dem jeweiligen Spieler.
 * <p>
 * Der erste Mensch ist "ich", alle KI-Spieler sind "Bots" (gibt es keine, dann die uebrigen Spieler).
 */
public final class Scenarios {

    private static final List<String> NAMES = List.of("swarm", "dredge", "necro", "gemstone", "convoke");

    private Scenarios() {
    }

    public static boolean exists(String name) {
        return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Hooks fuer {@link GameHost#setScenario}; unbekannte Namen: {@link IllegalArgumentException}. */
    public static ScenarioHooks hooks(String name) {
        if (!exists(name)) {
            throw new IllegalArgumentException("Unbekanntes Szenario: " + name);
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "swarm" -> new Hooks(Start.HUMAN, null, Scenarios::swarm);
            case "dredge" -> new Hooks(Start.BOT, null, Scenarios::dredge);
            case "necro" -> new Hooks(Start.HUMAN, null, Scenarios::necro);
            case "gemstone" -> new Hooks(Start.BOT, "Gemstone Caverns", t -> {
            });
            case "convoke" -> new Hooks(Start.HUMAN, null, Scenarios::convoke);
            default -> throw new IllegalArgumentException("Unbekanntes Szenario: " + name);
        };
    }

    // ------------------------------------------------------------------ Szenarien

    /**
     * {@code swarm}: lange Trigger-Ketten. Ich beginne mit 6 Waeldern + 16 Scute Swarm, auf der Hand 2 Waelder und
     * Giant Growth (damit ich etwas Spielbares habe). Jeder Bot: 2 Berge + Mogg Fanatic (immer aktivierbar),
     * Lightning Bolt auf der Hand - Bots haben also Antworten und muessen ueber Trigger "nachdenken".
     */
    private static void swarm(Table t) {
        t.battlefield(t.human, "Forest", 6);
        t.battlefield(t.human, "Scute Swarm", 16);
        t.hand(t.human, "Forest", "Forest", "Giant Growth");
        for (Player bot : t.bots) {
            t.battlefield(bot, "Mountain", 2);
            t.battlefield(bot, "Mogg Fanatic", 1);
            t.hand(bot, "Lightning Bolt");
        }
    }

    /**
     * {@code dredge}: Ersatzeffekt-Wahl beim Ziehen. Mein Friedhof: 5x Dredge 2 (gleicher Regeltext -> eine
     * Gruppe), Life from the Loam (Dredge 3) und Stinkweed Imp (Dredge 5) -> 3 Gruppen. 4 Waelder im Spiel.
     * Ein Bot beginnt, damit ich schon im ersten eigenen Zug ziehe.
     */
    private static void dredge(Table t) {
        t.battlefield(t.human, "Forest", 4);
        t.graveyard(t.human, "Dakmor Salvage", "Golgari Brownscale", "Moldervine Cloak", "Necroplasm", "Nightmare Void",
                "Life from the Loam", "Stinkweed Imp");
    }

    /** {@code necro}: Necropotence im Spiel, ich beginne. */
    private static void necro(Table t) {
        t.battlefield(t.human, "Necropotence", 1);
    }

    /**
     * {@code convoke}: X-Zauber und Einberufen. Ich beginne mit 3 Bergen, 2 Suempfen, Watchwolf (gruen-weiss),
     * 3 Grizzly Bears und 2 Savannah Lions; auf der Hand Blaze ({X}{R}) und Guardian of Vitu-Ghazi ({6}{G}{W},
     * Convoke). Keine gruenen/weissen Laender: G und W muessen die Kreaturen zahlen, Watchwolf bekommt die Farbwahl.
     */
    private static void convoke(Table t) {
        t.battlefield(t.human, "Mountain", 3);
        t.battlefield(t.human, "Swamp", 2);
        t.battlefield(t.human, "Watchwolf", 1);
        t.battlefield(t.human, "Grizzly Bears", 3);
        t.battlefield(t.human, "Savannah Lions", 2);
        t.hand(t.human, "Blaze", "Guardian of Vitu-Ghazi");
    }

    // ------------------------------------------------------------------ Hooks

    private enum Start { HUMAN, BOT }

    /**
     * {@code openingHandCard}: diese Karte kommt vor der ersten Mulligan-Frage in die Starthand des ersten Menschen
     * (statt einer anderen Karte; Szenario {@code gemstone}: "Starthand-Aktion", nur wenn ein Bot beginnt).
     */
    private record Hooks(Start start, String openingHandCard, Consumer<Table> setup) implements ScenarioHooks {

        @Override
        public Player startingPlayer(GameHost host) {
            Table t = Table.of(host);
            if (start == Start.HUMAN || t.bots.isEmpty()) {
                return t.human;
            }
            return t.bots.get(0);
        }

        @Override
        public void beforeMulligan(GameHost host, Player human) {
            if (openingHandCard == null) {
                return;
            }
            Table t = Table.of(host);
            if (human != t.human) {
                return; // nur der erste Mensch
            }
            Game game = host.getGame();
            Card out = null;
            for (Card c : human.getCardsIn(ZoneType.Hand)) {
                out = c;
            }
            if (out != null) {
                game.getAction().moveToBottomOfLibrary(out, null);
            }
            game.getAction().moveToHand(Table.create(game, human, openingHandCard), null);
        }

        @Override
        public void startGame(GameHost host) {
            setup.accept(Table.of(host));
        }
    }

    /** Spieler und Karten-Fabrik eines Szenarios (Spiel-Thread). */
    private static final class Table {
        final Game game;
        final Player human;
        final List<Player> bots;

        private Table(Game game, Player human, List<Player> bots) {
            this.game = game;
            this.human = human;
            this.bots = bots;
        }

        static Table of(GameHost host) {
            Game game = host.getGame();
            Player human = host.forgePlayer(host.getHumanId());
            List<Player> bots = new ArrayList<>();
            for (Player p : game.getPlayers()) {
                if (p != human && p.isAI()) {
                    bots.add(p);
                }
            }
            if (bots.isEmpty()) {
                for (Player p : game.getPlayers()) {
                    if (p != human) {
                        bots.add(p);
                    }
                }
            }
            return new Table(game, human, bots);
        }

        /** Neue Spielkarte ausserhalb jeder Zone (wie Forges Entwicklermenue "Karte hinzufuegen"). */
        static Card create(Game game, Player owner, String name) {
            PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
            if (pc == null) {
                throw new IllegalStateException("Karte nicht gefunden: " + name);
            }
            Card c = Card.fromPaperCard(pc, owner);
            c.setGameTimestamp(game.getNextTimestamp());
            return c;
        }

        void battlefield(Player p, String name, int n) {
            for (int i = 0; i < n; i++) {
                Card moved = game.getAction().moveTo(ZoneType.Battlefield, create(game, p, name), null, AbilityKey.newMap());
                if (moved != null) {
                    moved.setSickness(false); // laege sonst schon in Zug 1 "krank" auf dem Feld
                }
            }
        }

        void hand(Player p, String... names) {
            for (String n : names) {
                game.getAction().moveToHand(create(game, p, n), null);
            }
        }

        void graveyard(Player p, String... names) {
            for (String n : names) {
                game.getAction().moveToGraveyard(create(game, p, n), null);
            }
        }
    }
}
