package dev.magelite.stats;

import mage.MageObject;
import mage.cards.Card;
import mage.constants.CommanderCardType;
import mage.constants.WatcherScope;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.stack.Spell;
import mage.players.Player;
import mage.watchers.Watcher;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Leitet relevante Spielereignisse an den {@link StatsSink} weiter.
 * Keine Felder (XMage kopiert Watcher per Reflection fuer Simulationen); Simulationen werden ignoriert.
 */
public class StatsWatcher extends Watcher {

    public StatsWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (game.isSimulation()) {
            return;
        }
        for (StatsSink sink : StatsSink.all(game.getId())) {
            track(sink, event, game);
        }
    }

    private static void track(StatsSink sink, GameEvent event, Game game) {
        UUID human = sink.humanId();
        try {
            switch (event.getType()) {
                case BEGIN_TURN -> {
                    if (!sink.openingRecorded()) {
                        Player p = game.getPlayer(human);
                        if (p != null) {
                            List<String> names = new ArrayList<>();
                            for (Card c : p.getHand().getCards(game)) {
                                names.add(c.getName());
                            }
                            sink.opening(names);
                        }
                    }
                    if (human.equals(event.getPlayerId())) {
                        sink.humanTurn();
                    }
                }
                case DREW_CARD -> {
                    if (human.equals(event.getPlayerId())) {
                        Card c = game.getCard(event.getTargetId());
                        if (c != null) {
                            sink.drew(c.getName());
                        }
                    }
                }
                case SPELL_CAST -> {
                    if (human.equals(event.getPlayerId())) {
                        Spell spell = game.getSpell(event.getTargetId());
                        String name = spell != null ? spell.getCard().getMainCard().getName() : nameOf(game, event.getSourceId());
                        boolean commander = event.getZone() == Zone.COMMAND;
                        if (name != null) {
                            sink.cast(name, game.getTurnNum(), commander);
                        }
                    }
                }
                case LAND_PLAYED -> {
                    if (human.equals(event.getPlayerId())) {
                        String name = nameOf(game, event.getTargetId());
                        if (name != null) {
                            sink.landPlayed(name);
                        }
                    }
                }
                case DAMAGED_PLAYER -> {
                    UUID controller = game.getControllerId(event.getSourceId());
                    if (human.equals(controller) && !human.equals(event.getTargetId())) {
                        Player p = game.getPlayer(human);
                        boolean byCommander = p != null && game.getCommandersIds(p, CommanderCardType.COMMANDER_OR_OATHBREAKER, false)
                                .contains(event.getSourceId());
                        sink.damage(event.getAmount(), byCommander);
                    }
                }
                default -> {
                    // egal
                }
            }
        } catch (RuntimeException ignored) {
            // Statistik darf das Spiel nie stoeren
        }
    }

    private static String nameOf(Game game, UUID id) {
        MageObject o = game.getObject(id);
        if (o instanceof Card c) {
            return c.getMainCard().getName();
        }
        return o == null ? null : o.getName();
    }
}
