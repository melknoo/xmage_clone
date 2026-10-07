package dev.magelite.game;

import dev.magelite.view.GameViewMapper;
import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.Messages;
import mage.MageObject;
import mage.cards.Card;
import mage.constants.WatcherScope;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.DamagedEvent;
import mage.game.events.GameEvent;
import mage.game.events.ZoneChangeEvent;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import mage.view.CardView;
import mage.watchers.Watcher;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Meldet sichtbare Spielereignisse (Zonenwechsel, Schaden, Leben, Marken, Neutralisieren) als {@link Messages.FxEvent}
 * an den {@link GameHost}, der sie gebuendelt als {@code events} an die UI schickt (Mini-Animationen + Ereignisleiste,
 * damit man beim Auto-Passen nachvollziehen kann, was gerade verschwunden ist).
 * <p>
 * Keine Felder (XMage kopiert Watcher per Reflection fuer Simulationen); Simulationen werden ignoriert. Registry nach
 * Spiel-id wie {@code StatsSink}.
 */
public class FxWatcher extends Watcher {

    private static final Map<UUID, Consumer<Messages.FxEvent>> LISTENERS = new ConcurrentHashMap<>();

    public FxWatcher() {
        super(WatcherScope.GAME);
    }

    public static void listen(UUID gameId, Consumer<Messages.FxEvent> listener) {
        LISTENERS.put(gameId, listener);
    }

    public static void forget(UUID gameId) {
        LISTENERS.remove(gameId);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (game.isSimulation()) {
            return;
        }
        Consumer<Messages.FxEvent> l = LISTENERS.get(game.getId());
        if (l == null) {
            return;
        }
        try {
            Messages.FxEvent e = map(event, game);
            if (e != null) {
                l.accept(e);
            }
        } catch (RuntimeException ignored) {
            // Animationen duerfen das Spiel nie stoeren
        }
    }

    private static Messages.FxEvent map(GameEvent event, Game game) {
        long ts = System.currentTimeMillis();
        switch (event.getType()) {
            case ZONE_CHANGE -> {
                if (!(event instanceof ZoneChangeEvent z)) {
                    return null;
                }
                return zoneChange(z, game, ts);
            }
            case DAMAGED_PLAYER -> {
                boolean combat = event instanceof DamagedEvent d && d.isCombatDamage();
                return new Messages.FxEvent("damage", null, null, null, null, null, event.getTargetId(), null,
                        event.getSourceId(), nameOf(game, event.getSourceId()), event.getAmount(), null, combat ? true : null, null, ts);
            }
            case DAMAGED_PERMANENT -> {
                boolean combat = event instanceof DamagedEvent d && d.isCombatDamage();
                Permanent p = game.getPermanent(event.getTargetId());
                return new Messages.FxEvent("damage", event.getTargetId(), p == null ? null : p.getName(), null, null, null,
                        p == null ? null : p.getControllerId(), null, event.getSourceId(), nameOf(game, event.getSourceId()),
                        event.getAmount(), null, combat ? true : null, null, ts);
            }
            case GAINED_LIFE -> {
                return new Messages.FxEvent("life", null, null, null, null, null, playerOf(event), null, event.getSourceId(),
                        nameOf(game, event.getSourceId()), event.getAmount(), null, null, null, ts);
            }
            case LOST_LIFE -> {
                return new Messages.FxEvent("life", null, null, null, null, null, playerOf(event), null, event.getSourceId(),
                        nameOf(game, event.getSourceId()), -event.getAmount(), null, null, null, ts);
            }
            case COUNTERS_ADDED -> {
                // einmal pro Batch (COUNTER_ADDED feuert zusaetzlich je Marke)
                Permanent p = game.getPermanent(event.getTargetId());
                if (p == null) {
                    return null; // Spieler-Marken (Gift, Energie) zeigt die Spieleranzeige selbst
                }
                return new Messages.FxEvent("counter", event.getTargetId(), event.getData(), null, null, null, p.getControllerId(),
                        null, event.getSourceId(), p.getName(), event.getAmount(), null, null, null, ts);
            }
            case COUNTERED -> {
                String name = nameOf(game, event.getTargetId());
                if (name == null) {
                    Card c = game.getCard(event.getTargetId());
                    name = c == null ? null : c.getName();
                }
                return new Messages.FxEvent("countered", event.getTargetId(), name, null, "STACK", "GRAVEYARD",
                        game.getControllerId(event.getTargetId()), null, event.getSourceId(), nameOf(game, event.getSourceId()),
                        null, null, null, null, ts);
            }
            default -> {
                return null;
            }
        }
    }

    private static Messages.FxEvent zoneChange(ZoneChangeEvent z, Game game, long ts) {
        Zone from = z.getFromZone();
        Zone to = z.getToZone();
        if (from == null || to == null || from == to) {
            return null;
        }
        Card c = z.getTarget() != null ? z.getTarget() : game.getCard(z.getTargetId());
        if (c == null) {
            return null;
        }
        String kind;
        if (z.isDiesEvent()) {
            kind = c instanceof PermanentToken ? "tokenDied" : "died";
        } else if (to == Zone.EXILED) {
            kind = "exiled";
        } else if (from == Zone.BATTLEFIELD && to == Zone.HAND) {
            kind = "bounced";
        } else if (to == Zone.LIBRARY && from != Zone.LIBRARY) {
            kind = "tucked";
        } else if (from == Zone.HAND && to == Zone.GRAVEYARD) {
            kind = "discarded";
        } else if (from == Zone.LIBRARY && to == Zone.GRAVEYARD) {
            kind = "milled";
        } else if (from == Zone.STACK && to == Zone.GRAVEYARD) {
            kind = "resolved";
        } else if (to == Zone.COMMAND && from == Zone.BATTLEFIELD) {
            kind = "command";
        } else {
            return null; // Ziehen, Ausspielen, Stapel->Spielfeld usw.: zeigt der State selbst
        }
        boolean secret = from == Zone.LIBRARY || from == Zone.HAND;
        // verdecktes Exil aus Hand/Bibliothek (Necropotence, Foretell ...): XMage dreht die Karte erst NACH dem
        // Zonenwechsel um (ForetellAbility: moveCardsToExile, dann setFaceDown) - isFaceDown greift hier noch nicht
        boolean hidden = (secret && (to == Zone.LIBRARY || to == Zone.HAND)) || c.isFaceDown(game)
                || (to == Zone.EXILED && secret && z.getSource() != null && isFaceDownExile(z));
        CardDto card = null;
        if (!hidden) {
            try {
                card = GameViewMapper.card(new CardView(c, game));
                card.rules = null;
                card.counters = null;
                card.targets = null;
                card.targetRefs = null;
                card.back = null;
            } catch (RuntimeException ignored) {
                card = null;
            }
        }
        UUID controller = game.getControllerId(c.getId());
        UUID owner = c.getOwnerId();
        UUID sourceId = z.getSource() == null ? null : z.getSource().getSourceId();
        return new Messages.FxEvent(kind, c.getId(), hidden ? null : c.getName(), card, from.name(), to.name(),
                controller != null ? controller : owner, owner, sourceId, sourceId == null ? null : nameOf(game, sourceId),
                null, c instanceof PermanentToken ? true : null, null, hidden ? true : null, ts);
    }

    /**
     * Verdeckt-Exil (Necropotence, Foretell & Co.) erkennt man im Ereignis nicht sicher - Regeltext der Quelle als
     * Heuristik ("face down" oder "foretell"; lieber zu viel verdecken als eine Handkarte zeigen).
     */
    private static boolean isFaceDownExile(ZoneChangeEvent z) {
        try {
            String rule = z.getSource().getRule();
            String l = rule == null ? "" : rule.toLowerCase(java.util.Locale.ROOT);
            return l.contains("face down") || l.contains("face-down") || l.contains("foretell");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static UUID playerOf(GameEvent event) {
        return event.getPlayerId() != null ? event.getPlayerId() : event.getTargetId();
    }

    private static String nameOf(Game game, UUID id) {
        if (id == null) {
            return null;
        }
        MageObject o = game.getObject(id);
        // Verdeckte Objekte (Morph/Manifest/Disguise, verdecktes Exil): nie den Namen der Karte darunter verraten
        if (o instanceof Permanent perm && perm.isFaceDown(game)) {
            return null;
        }
        if (o instanceof Card c) {
            if (c.isFaceDown(game) || c.getMainCard().isFaceDown(game)) {
                return null;
            }
            return c.getMainCard().getName();
        }
        return o == null ? null : o.getName();
    }
}
