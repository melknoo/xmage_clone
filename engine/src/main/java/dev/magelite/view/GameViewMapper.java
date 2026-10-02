package dev.magelite.view;

import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.CombatDto;
import dev.magelite.view.dto.CommandDto;
import dev.magelite.view.dto.CounterDto;
import dev.magelite.view.dto.NamedCardsDto;
import dev.magelite.view.dto.PermanentDto;
import dev.magelite.view.dto.PlayerDto;
import dev.magelite.view.dto.StateDto;
import mage.MageObject;
import mage.abilities.ActivatedAbility;
import mage.abilities.mana.ManaAbility;
import mage.ObjectColor;
import mage.cards.Card;
import mage.cards.Cards;
import mage.constants.CommanderCardType;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.players.Player;
import mage.view.CardView;
import mage.view.CardsView;
import mage.view.CombatGroupView;
import mage.view.CommandObjectView;
import mage.view.CommanderView;
import mage.view.CounterView;
import mage.view.EmblemView;
import mage.view.GameView;
import mage.view.ManaPoolView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.RevealedView;
import mage.view.StackAbilityView;
import mage.watchers.common.CommanderInfoWatcher;
import mage.watchers.common.CommanderPlaysCountWatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Baut aus {@link GameView} den schlanken {@link StateDto}. Muss auf dem Game-Thread laufen
 * (liest zusaetzlich direkt aus {@link Game}, z.B. Commander-Watcher).
 */
public final class GameViewMapper {

    private GameViewMapper() {
    }

    public static StateDto map(Game game, UUID myId, long seq, boolean withPlayable, UUID thinkingPlayerId,
                               Map<UUID, String> deckNames) {
        GameView gv = new GameView(game.getState(), game, myId, null);
        Player me = game.getPlayer(myId);

        StateDto s = new StateDto();
        s.seq = seq;
        s.turn = gv.getTurn();
        s.phase = gv.getPhase() == null ? null : gv.getPhase().name();
        s.step = gv.getStep() == null ? null : gv.getStep().name();
        s.activePlayerId = gv.getActivePlayerId();
        s.priorityPlayerId = game.getState().getPriorityPlayerId();
        s.myPlayerId = myId;

        // Kampf: wer greift an / blockt
        Set<UUID> attacking = new HashSet<>();
        Set<UUID> blocking = new HashSet<>();
        List<CombatDto> combat = new ArrayList<>();
        for (CombatGroupView g : gv.getCombat()) {
            List<UUID> att = new ArrayList<>(g.getAttackers().keySet());
            List<UUID> blk = new ArrayList<>(g.getBlockers().keySet());
            attacking.addAll(att);
            blocking.addAll(blk);
            combat.add(new CombatDto(g.getDefenderId(), g.getDefenderName(), att, blk, g.isBlocked()));
        }
        s.combat = combat;

        Map<UUID, PlayerView> views = new HashMap<>();
        for (PlayerView pv : gv.getPlayers()) {
            views.put(pv.getPlayerId(), pv);
        }
        CommanderPlaysCountWatcher playsWatcher = game.getState().getWatcher(CommanderPlaysCountWatcher.class);

        // Sitzordnung = Zugreihenfolge, beginnend mit mir; ausgeschiedene Spieler bleiben sichtbar
        List<UUID> order = new ArrayList<>(game.getState().getPlayers().keySet());
        int meIdx = order.indexOf(myId);
        if (meIdx > 0) {
            java.util.Collections.rotate(order, -meIdx);
        }
        List<PlayerDto> players = new ArrayList<>();
        for (UUID pid : order) {
            PlayerView pv = views.get(pid);
            Player p = game.getPlayer(pid);
            if (pv == null || p == null) {
                continue;
            }
            players.add(mapPlayer(game, pv, p, myId, attacking, blocking, playsWatcher, thinkingPlayerId, deckNames));
        }
        s.players = players;

        s.hand = cards(gv.getMyHand().values());

        List<CardDto> stack = new ArrayList<>();
        for (CardView v : gv.getStack().values()) {
            CardDto d = card(v);
            if (v instanceof StackAbilityView sav) {
                d.kind = "ability";
                CardView src = sav.getSourceCard();
                if (src != null) {
                    d.sourceId = src.getId();
                    d.set = src.getExpansionSetCode();
                    d.num = src.getCardNumber();
                    if (src.isToken()) {
                        d.image = src.getImageFileName();
                        d.imageNum = src.getImageNumber();
                        d.token = true;
                    }
                }
            } else {
                d.kind = "spell";
            }
            MageObject obj = game.getObject(v.getId());
            if (obj != null) {
                d.controllerId = game.getControllerId(v.getId());
            }
            stack.add(d);
        }
        s.stack = stack;

        if (!gv.getRevealed().isEmpty()) {
            List<NamedCardsDto> rev = new ArrayList<>();
            for (RevealedView r : gv.getRevealed()) {
                rev.add(new NamedCardsDto(r.getName(), cards(r.getCards().values())));
            }
            s.revealed = rev;
        }
        Map<String, Cards> looked = game.getState().getLookedAt(myId);
        if (looked != null && !looked.isEmpty()) {
            List<NamedCardsDto> la = new ArrayList<>();
            for (Map.Entry<String, Cards> e : looked.entrySet()) {
                la.add(new NamedCardsDto(e.getKey(), cards(new CardsView(game, e.getValue().getCards(game), myId).values())));
            }
            s.lookedAt = la;
        }

        if (withPlayable && me != null) {
            Playable pl = playable(game, me);
            s.playable = pl.all();
            s.actions = pl.actions();
        }
        return s;
    }

    /**
     * Spielbare Objekte des Spielers (oder des von ihm kontrollierten Prioritaetsspielers).
     * {@code all}: id -> Anzahl spielbarer Faehigkeiten; {@code actions}: Objekte mit Nicht-Mana-Aktionen
     * (Land spielen, Zauber, aktivierte Faehigkeiten) - dafuer glueht die Karte im Prioritaets-Modus.
     */
    public static Playable playable(Game game, Player me) {
        Player priorityPlayer = game.getPlayer(game.getState().getPriorityPlayerId());
        Player source = me;
        if (priorityPlayer != null && me.getId().equals(priorityPlayer.getTurnControlledBy())) {
            source = priorityPlayer;
        }
        Map<UUID, Integer> all = new LinkedHashMap<>();
        Set<UUID> actions = new LinkedHashSet<>();
        for (ActivatedAbility ability : source.getPlayable(game, true)) {
            UUID sourceId = ability.getSourceId();
            if (sourceId == null) {
                continue;
            }
            boolean mana = ability instanceof ManaAbility;
            List<UUID> ids = new ArrayList<>(3);
            ids.add(sourceId);
            Card card = game.getCard(sourceId);
            if (card != null && !card.getMainCard().getId().equals(card.getId())) {
                ids.add(card.getMainCard().getId());
            }
            Spell spell = game.getSpell(sourceId);
            if (spell != null) {
                ids.add(spell.getId());
            }
            for (UUID id : ids) {
                all.merge(id, 1, Integer::sum);
                if (!mana) {
                    actions.add(id);
                }
            }
        }
        return new Playable(all, new ArrayList<>(actions));
    }

    public record Playable(Map<UUID, Integer> all, List<UUID> actions) {
        public boolean hasActions() {
            return !actions.isEmpty();
        }
    }

    private static PlayerDto mapPlayer(Game game, PlayerView pv, Player p, UUID myId, Set<UUID> attacking, Set<UUID> blocking,
                                       CommanderPlaysCountWatcher playsWatcher, UUID thinkingPlayerId, Map<UUID, String> deckNames) {
        PlayerDto d = new PlayerDto();
        d.id = pv.getPlayerId();
        d.name = pv.getName();
        d.me = d.id.equals(myId);
        d.human = p.isHuman();
        d.life = pv.getLife();
        d.counters = counters(pv.getCounters());
        d.library = pv.getLibraryCount();
        d.handCount = pv.getHandCount();
        d.graveyard = cards(pv.getGraveyard().values());
        d.exile = cards(pv.getExile().values());
        d.mana = mana(pv.getManaPool());
        d.active = pv.isActive();
        d.priority = pv.hasPriority();
        d.lost = p.hasLost() || pv.hasLeft();
        d.won = p.hasWon();
        d.monarch = pv.isMonarch();
        d.initiative = pv.isInitiative();
        d.thinking = d.id.equals(thinkingPlayerId);
        d.deckName = deckNames == null ? null : deckNames.get(d.id);

        List<String> skips = new ArrayList<>();
        if (pv.isPassedTurn()) skips.add("nextTurn");
        if (pv.isPassedUntilEndOfTurn()) skips.add("endOfTurn");
        if (pv.isPassedUntilNextMain()) skips.add("nextMain");
        if (pv.isPassedAllTurns()) skips.add("myTurn");
        if (pv.isPassedUntilStackResolved()) skips.add("stackResolved");
        if (pv.isPassedUntilEndStepBeforeMyTurn()) skips.add("endStepBeforeMyTurn");
        d.skips = skips.isEmpty() ? null : skips;

        List<PermanentDto> bf = new ArrayList<>();
        for (PermanentView v : pv.getBattlefield().values()) {
            bf.add(permanent(game, v, attacking, blocking));
        }
        d.battlefield = bf;

        List<CommandDto> command = new ArrayList<>();
        for (CommandObjectView co : pv.getCommandObjectList()) {
            CommandDto c = new CommandDto();
            c.id = co.getId();
            c.name = co.getName();
            c.set = co.getExpansionSetCode();
            c.image = co.getImageFileName();
            c.imageNum = co.getImageNumber();
            c.rules = co.getRules();
            if (co instanceof CommanderView cv) {
                c.kind = "commander";
                c.num = cv.getCardNumber();
                c.card = card(cv);
                c.image = null;
                c.imageNum = 0;
            } else if (co instanceof EmblemView ev) {
                c.kind = "emblem";
                c.num = ev.getCardNumber();
            } else {
                c.kind = "other";
            }
            command.add(c);
        }
        // Commander-Steuer fuer alle Commander (auch wenn gerade auf dem Spielfeld)
        Map<String, Integer> cmdDamage = new LinkedHashMap<>();
        for (Player opp : game.getState().getPlayers().values()) {
            for (UUID cmdId : game.getCommandersIds(opp, CommanderCardType.COMMANDER_OR_OATHBREAKER, false)) {
                commanderName(game, cmdId); // Namen merken, solange die Karte existiert
                CommanderInfoWatcher w = game.getState().getWatcher(CommanderInfoWatcher.class, cmdId);
                if (w != null) {
                    Integer dmg = w.getDamageToPlayer().get(p.getId());
                    if (dmg != null && dmg > 0) {
                        cmdDamage.put(commanderName(game, cmdId), dmg);
                    }
                }
            }
        }
        d.commanderDamage = cmdDamage.isEmpty() ? null : cmdDamage;
        if (playsWatcher != null) {
            for (CommandDto c : command) {
                if ("commander".equals(c.kind)) {
                    c.casts = playsWatcher.getPlaysCount(c.id);
                    c.tax = 2 * c.casts;
                }
            }
            // Commander auf dem Spielfeld/anderswo: trotzdem Casts anzeigen
            for (UUID cmdId : game.getCommandersIds(p, CommanderCardType.COMMANDER_OR_OATHBREAKER, false)) {
                boolean listed = command.stream().anyMatch(c -> cmdId.equals(c.id));
                if (!listed) {
                    Card card = game.getCard(cmdId);
                    if (card != null) {
                        CommandDto c = new CommandDto();
                        c.id = cmdId;
                        c.kind = "commander-away";
                        c.name = card.getName();
                        c.set = card.getExpansionSetCode();
                        c.num = card.getCardNumber();
                        c.casts = playsWatcher.getPlaysCount(cmdId);
                        c.tax = 2 * c.casts;
                        Zone z = game.getState().getZone(cmdId);
                        c.rules = List.of("Zone: " + (z == null ? "?" : z.name()));
                        command.add(c);
                    }
                }
            }
        }
        d.command = command;
        return d;
    }

    private static final Map<UUID, String> COMMANDER_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    private static String commanderName(Game game, UUID id) {
        Card c = game.getCard(id);
        if (c != null) {
            COMMANDER_NAMES.put(id, c.getName());
            return c.getName();
        }
        MageObject o = game.getObject(id);
        if (o != null) {
            return o.getName();
        }
        return COMMANDER_NAMES.getOrDefault(id, "Commander");
    }

    private static PermanentDto permanent(Game game, PermanentView v, Set<UUID> attacking, Set<UUID> blocking) {
        PermanentDto d = new PermanentDto();
        fill(d, v);
        d.tapped = v.isTapped();
        d.damage = v.getDamage();
        d.sick = v.hasSummoningSickness();
        d.copy = v.isCopy();
        d.phasedOut = !v.isPhasedIn();
        d.flipped = v.isFlipped();
        d.attachments = v.getAttachments() == null || v.getAttachments().isEmpty() ? null : new ArrayList<>(v.getAttachments());
        d.attachedTo = v.getAttachedTo();
        d.attacking = attacking.contains(v.getId());
        d.blocking = blocking.contains(v.getId());
        d.canAttack = v.isCanAttack();
        d.canBlock = v.isCanBlock();
        Permanent perm = game.getPermanent(v.getId());
        if (perm != null) {
            d.controllerId = perm.getControllerId();
            d.ownerId = perm.getOwnerId();
        }
        if (v.isCreature()) {
            d.row = "creature";
        } else if (v.isLand()) {
            d.row = "land";
        } else {
            d.row = "other";
        }
        return d;
    }

    public static List<CardDto> cards(Collection<? extends CardView> views) {
        List<CardDto> out = new ArrayList<>(views.size());
        for (CardView v : views) {
            out.add(card(v));
        }
        return out;
    }

    public static CardDto card(CardView v) {
        CardDto d = new CardDto();
        fill(d, v);
        return d;
    }

    private static void fill(CardDto d, CardView v) {
        d.id = v.getId();
        d.name = v.getDisplayName();
        d.set = v.getExpansionSetCode();
        d.num = v.getCardNumber();
        if (v.isToken()) {
            d.image = v.getImageFileName();
            d.imageNum = v.getImageNumber();
        }
        d.manaCost = emptyToNull(v.getManaCostStr());
        d.mv = v.getManaValue();
        d.typeLine = emptyToNull(v.getTypeText());
        if (v.getCardTypes() != null && !v.getCardTypes().isEmpty()) {
            List<String> types = new ArrayList<>();
            v.getCardTypes().forEach(t -> types.add(t.name()));
            d.types = types;
        }
        d.colors = colors(v.getColor());
        if (v.showPT()) {
            d.power = v.getPower();
            d.toughness = v.getToughness();
        }
        if (v.isPlaneswalker()) {
            d.loyalty = emptyToNull(v.getLoyalty());
        }
        if (v.isBattle()) {
            d.defense = emptyToNull(v.getDefense());
        }
        d.rarity = v.getRarity() == null ? null : v.getRarity().getCode();
        d.rules = v.getRules() == null || v.getRules().isEmpty() ? null : v.getRules();
        d.counters = counters(v.getCounters());
        d.token = v.isToken();
        d.faceDown = v.isFaceDown();
        d.transformable = v.canTransform();
        d.transformed = v.isTransformed();
        if (v.canTransform() && v.getSecondCardFace() != null) {
            CardView b = v.getSecondCardFace();
            CardDto back = new CardDto();
            back.id = b.getId();
            back.name = b.getDisplayName();
            back.set = b.getExpansionSetCode();
            back.num = b.getCardNumber();
            back.manaCost = emptyToNull(b.getManaCostStr());
            back.typeLine = emptyToNull(b.getTypeText());
            back.rules = b.getRules();
            if (b.showPT()) {
                back.power = b.getPower();
                back.toughness = b.getToughness();
            }
            d.back = back;
        }
        d.targets = v.getTargets() == null || v.getTargets().isEmpty() ? null : new ArrayList<>(v.getTargets());
    }

    private static List<CounterDto> counters(List<CounterView> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<CounterDto> out = new ArrayList<>(list.size());
        for (CounterView c : list) {
            out.add(new CounterDto(c.getName(), c.getCount()));
        }
        return out;
    }

    private static Map<String, Integer> mana(ManaPoolView m) {
        if (m == null) {
            return null;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        putIfPositive(out, "W", m.getWhite());
        putIfPositive(out, "U", m.getBlue());
        putIfPositive(out, "B", m.getBlack());
        putIfPositive(out, "R", m.getRed());
        putIfPositive(out, "G", m.getGreen());
        putIfPositive(out, "C", m.getColorless());
        return out.isEmpty() ? null : out;
    }

    private static void putIfPositive(Map<String, Integer> m, String k, int v) {
        if (v > 0) {
            m.put(k, v);
        }
    }

    private static String colors(ObjectColor c) {
        if (c == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (c.isWhite()) sb.append('W');
        if (c.isBlue()) sb.append('U');
        if (c.isBlack()) sb.append('B');
        if (c.isRed()) sb.append('R');
        if (c.isGreen()) sb.append('G');
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
