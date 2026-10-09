package dev.magelite.view;

import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.CombatDto;
import dev.magelite.view.dto.CommandDto;
import dev.magelite.view.dto.CounterDto;
import dev.magelite.view.dto.PermanentDto;
import dev.magelite.view.dto.PlayerDto;
import dev.magelite.view.dto.StateDto;
import dev.magelite.view.dto.TargetRefDto;
import com.google.common.collect.Multiset;
import forge.StaticData;
import forge.ai.AvailableActions;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.card.CardEdition;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.AttackingBand;
import forge.game.keyword.Keyword;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.player.LobbyPlayerHuman;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Baut aus dem lebenden Forge-{@link Game} den schlanken {@link StateDto} (Sicht eines Sitzes oder der Zuschauer).
 * Nur auf dem Spiel-Thread aufrufen (Forge mutiert die Objekte dort).
 * <p>
 * Sichtbarkeit: {@link CardView#canBeShownTo} je Betrachter; Zuschauer sehen nur oeffentliche Zonen und keine
 * verdeckten Karten.
 */
public final class ForgeViewMapper {

    /** Name eines verdeckt gewirkten Zaubers fuer Fremde (Ziel-Anzeige). */
    public static final String FACE_DOWN_SPELL = "verdeckter Zauber";

    private final Game game;
    private final IdCodec ids;

    public ForgeViewMapper(Game game, IdCodec ids) {
        this.game = game;
        this.ids = ids;
    }

    public IdCodec ids() {
        return ids;
    }

    /** Spielbare Objekte eines Sitzes: id -> Anzahl Faehigkeiten; {@code actions} = Objekte mit Nicht-Mana-Aktionen. */
    public record Playable(Map<UUID, Integer> all, List<UUID> actions) {
        public boolean hasActions() {
            return !actions.isEmpty();
        }
    }

    // ---- State ----------------------------------------------------------------------------------------------------------

    public StateDto map(Player viewer, long seq, Playable playable, Player thinking, Map<UUID, String> deckNames) {
        return build(viewer, viewer, seq, playable, thinking, deckNames);
    }

    /**
     * Zuschauer-Sicht: Betrachter null (nur oeffentliche Informationen), Sitzordnung ab {@code viewpoint},
     * {@code me} nur am Blickwinkel-Spieler, keine Hand/playable/actions.
     */
    public StateDto mapPublic(Player viewpoint, long seq, Player thinking, Map<UUID, String> deckNames) {
        StateDto s = build(null, viewpoint, seq, null, thinking, deckNames);
        s.myPlayerId = null;
        s.hand = List.of();
        s.lookedAt = null;
        s.playable = null;
        s.actions = null;
        s.replDeclines = null;
        s.spectator = Boolean.TRUE;
        for (PlayerDto p : s.players) {
            p.me = viewpoint != null && p.id.equals(ids.player(viewpoint.getId()));
            p.topCardPrivate = false;
        }
        return s;
    }

    private StateDto build(Player viewer, Player orderFrom, long seq, Playable pl, Player thinking, Map<UUID, String> deckNames) {
        PlayerView vv = viewer == null ? null : viewer.getView();
        PhaseHandler ph = game.getPhaseHandler();
        StateDto s = new StateDto();
        s.seq = seq;
        s.turn = ph.getTurn();
        s.phase = ph.getTurn() == 0 ? null : WireNames.phase(ph.getPhase());
        s.step = ph.getTurn() == 0 ? null : WireNames.step(ph.getPhase());
        s.activePlayerId = ph.getPlayerTurn() == null ? null : ids.player(ph.getPlayerTurn().getId());
        s.priorityPlayerId = ph.getPriorityPlayer() == null ? null : ids.player(ph.getPriorityPlayer().getId());
        s.myPlayerId = viewer == null ? null : ids.player(viewer.getId());

        Set<Integer> attacking = new HashSet<>();
        Set<Integer> blocking = new HashSet<>();
        s.combat = combat(attacking, blocking);

        List<PlayerDto> players = new ArrayList<>();
        for (Player p : seatOrder(orderFrom)) {
            players.add(player(p, viewer, vv, attacking, blocking, thinking, deckNames));
        }
        s.players = players;
        s.hand = viewer == null ? List.of() : cards(viewer.getCardsIn(ZoneType.Hand), vv);
        s.stack = stack(vv);
        if (pl != null) {
            s.playable = pl.all();
            s.actions = pl.actions();
        }
        return s;
    }

    /** Sitzordnung = Zugreihenfolge ab {@code from}; ausgeschiedene Spieler bleiben gelistet. */
    public List<Player> seatOrder(Player from) {
        List<Player> all = new ArrayList<>(game.getRegisteredPlayers());
        int start = from == null ? 0 : Math.max(0, all.indexOf(from));
        List<Player> out = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            out.add(all.get((start + i) % all.size()));
        }
        return out;
    }

    private List<CombatDto> combat(Set<Integer> attacking, Set<Integer> blocking) {
        List<CombatDto> out = new ArrayList<>();
        Combat combat = game.getCombat();
        if (combat == null) {
            return out;
        }
        for (AttackingBand band : combat.getAttackingBands()) {
            GameEntity def = combat.getDefenderByAttacker(band);
            List<UUID> att = new ArrayList<>();
            for (Card c : band.getAttackers()) {
                att.add(ids.card(c.getId()));
                attacking.add(c.getId());
            }
            List<UUID> blk = new ArrayList<>();
            for (Card c : combat.getBlockers(band)) {
                blk.add(ids.card(c.getId()));
                blocking.add(c.getId());
            }
            Boolean blocked = band.isBlocked();
            out.add(new CombatDto(entityId(def), def == null ? null : def.getName(), att, blk, blocked != null && blocked));
        }
        return out;
    }

    private PlayerDto player(Player p, Player viewer, PlayerView vv, Set<Integer> attacking, Set<Integer> blocking, Player thinking,
                             Map<UUID, String> deckNames) {
        PhaseHandler ph = game.getPhaseHandler();
        PlayerDto d = new PlayerDto();
        d.id = ids.player(p.getId());
        d.name = p.getName();
        d.me = p == viewer;
        d.human = p.getLobbyPlayer() instanceof LobbyPlayerHuman;
        d.life = p.getLife();
        d.counters = counters(p.getCounters());
        d.library = p.getCardsIn(ZoneType.Library).size();
        d.handCount = p.getCardsIn(ZoneType.Hand).size();
        d.graveyard = cards(p.getCardsIn(ZoneType.Graveyard), vv);
        d.exile = cards(p.getCardsIn(ZoneType.Exile), vv);
        d.mana = mana(p);
        d.active = ph.getPlayerTurn() == p;
        d.priority = ph.getPriorityPlayer() == p;
        d.lost = p.hasLost();
        d.won = p.hasWon();
        d.monarch = p.isMonarch();
        d.initiative = game.getHasInitiative() == p;
        d.thinking = p == thinking;
        d.deckName = deckNames == null ? null : deckNames.get(d.id);
        topCard(d, p, viewer, vv);

        List<PermanentDto> bf = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            bf.add(permanent(c, vv, attacking, blocking));
        }
        d.battlefield = bf;
        d.command = command(p, viewer, vv);

        Map<String, Integer> cmdDamage = new LinkedHashMap<>();
        for (Map.Entry<Card, Integer> e : p.getCommanderDamage()) {
            if (e.getValue() != null && e.getValue() > 0) {
                cmdDamage.merge(e.getKey().getName(), e.getValue(), Integer::sum);
            }
        }
        d.commanderDamage = cmdDamage.isEmpty() ? null : cmdDamage;
        return d;
    }

    /** Oberste Bibliothekskarte, wenn der Betrachter sie sehen darf (aufgedeckt oder "darf ansehen"). */
    private void topCard(PlayerDto d, Player p, Player viewer, PlayerView vv) {
        Card top = p.getCardsIn(ZoneType.Library).isEmpty() ? null : p.getCardsIn(ZoneType.Library).get(0);
        if (top == null) {
            return;
        }
        CardView cv = top.getView();
        boolean publicTop = true;
        for (Player o : game.getRegisteredPlayers()) {
            if (!cv.canBeShownTo(o.getView())) {
                publicTop = false;
                break;
            }
        }
        if (publicTop) {
            d.topCard = card(top, vv);
        } else if (viewer != null && cv.canBeShownTo(vv)) {
            d.topCard = card(top, vv);
            d.topCardPrivate = true;
        }
    }

    private List<CommandDto> command(Player p, Player viewer, PlayerView vv) {
        List<CommandDto> out = new ArrayList<>();
        List<Card> commanders = p.getCommanders();
        for (Card c : p.getCardsIn(ZoneType.Command)) {
            boolean isCommander = commanders.contains(c);
            if (!isCommander && c.isImmutable() && !c.isEmblem()) {
                continue; // unsichtbare Effekt-Karten
            }
            CommandDto cd = new CommandDto();
            cd.id = ids.card(c.getId());
            cd.name = c.getName();
            CardDto card = card(c, vv);
            cd.set = card.set;
            cd.num = card.num;
            cd.rules = card.rules;
            if (isCommander) {
                cd.kind = "commander";
                cd.card = card;
                cd.casts = p.getCommanderCast(c);
                cd.tax = 2 * cd.casts;
            } else if (c.isEmblem()) {
                cd.kind = "emblem";
            } else {
                cd.kind = "other";
            }
            out.add(cd);
        }
        for (Card c : commanders) {
            if (c.isInZone(ZoneType.Command)) {
                continue;
            }
            CommandDto cd = new CommandDto();
            cd.kind = "commander-away";
            cd.id = ids.card(c.getId());
            cd.name = c.getName();
            IPaperCard pc = c.getPaperCard();
            cd.set = scryfallSet(pc);
            cd.num = number(pc);
            cd.casts = p.getCommanderCast(c);
            cd.tax = 2 * cd.casts;
            ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
            boolean secret = (z == ZoneType.Hand || z == ZoneType.Library || c.isFaceDown()) && p != viewer;
            if (secret) {
                cd.id = null;
                cd.rules = List.of("Zone: verborgen");
            } else {
                cd.rules = List.of("Zone: " + (z == null ? "?" : WireNames.zone(z)));
            }
            out.add(cd);
        }
        return out;
    }

    private List<CardDto> stack(PlayerView vv) {
        List<CardDto> out = new ArrayList<>();
        for (SpellAbilityStackInstance si : game.getStack()) {
            Card src = si.getSourceCard();
            SpellAbility sa = si.getSpellAbility();
            CardDto d;
            if (si.isSpell() && src != null) {
                d = card(src, vv);
                d.kind = "spell";
            } else {
                d = new CardDto();
                d.id = ids.encode(IdCodec.Kind.STACK, si.getId());
                d.kind = "ability";
                d.abilityType = si.isTrigger() ? "triggered" : sa != null && sa.isManaAbility() ? "mana"
                        : sa != null && sa.isActivatedAbility() ? "activated" : "static";
                if (src != null) {
                    boolean hidden = !src.getView().canBeShownTo(vv) || (src.isFaceDown() && !src.getView().canFaceDownBeShownTo(vv));
                    d.name = hidden ? "" : src.getName();
                    d.sourceId = ids.card(src.getId());
                    if (!hidden) {
                        IPaperCard pc = src.getPaperCard();
                        d.set = scryfallSet(pc);
                        d.num = number(pc);
                        d.token = src.isToken();
                        if (src.isToken()) {
                            d.image = src.getName();
                        }
                    }
                }
                String desc = si.getStackDescription();
                d.rules = desc == null || desc.isBlank() ? null : List.of(desc);
            }
            if (si.getActivatingPlayer() != null) {
                d.controllerId = ids.player(si.getActivatingPlayer().getId());
            }
            if (sa != null) {
                Integer x = sa.getXManaCostPaid();
                d.x = x;
            }
            List<TargetRefDto> refs = targetRefs(si.getTargetChoices(), vv);
            if (refs != null) {
                d.targetRefs = refs;
                d.targets = refs.stream().map(t -> t.id).toList();
            }
            out.add(d);
        }
        return out;
    }

    private List<TargetRefDto> targetRefs(TargetChoices tc, PlayerView vv) {
        if (tc == null) {
            return null;
        }
        List<TargetRefDto> out = new ArrayList<>();
        for (GameObject o : tc) {
            TargetRefDto t = new TargetRefDto();
            if (o instanceof Player p) {
                t.id = ids.player(p.getId());
                t.kind = "player";
                t.name = p.getName();
            } else if (o instanceof Card c) {
                t.id = ids.card(c.getId());
                ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
                boolean visible = c.getView().canBeShownTo(vv) && (!c.isFaceDown() || c.getView().canFaceDownBeShownTo(vv));
                if (z == ZoneType.Battlefield) {
                    t.kind = "permanent";
                    t.name = visible ? c.getName() : "verdecktes Permanent";
                    t.owner = c.getController() == null ? null : c.getController().getName();
                } else if (z == ZoneType.Stack) {
                    t.kind = "spell";
                    t.name = visible ? c.getName() : FACE_DOWN_SPELL;
                    t.owner = c.getController() == null ? null : c.getController().getName();
                } else {
                    t.kind = "card";
                    t.zone = WireNames.zone(z);
                    t.name = visible ? c.getName() : "verdeckte Karte";
                    t.owner = c.getOwner() == null ? null : c.getOwner().getName();
                }
            } else if (o instanceof SpellAbility sa) {
                SpellAbilityStackInstance si = game.getStack().getInstanceMatchingSpellAbilityID(sa);
                t.kind = "spell";
                t.id = si == null ? null : si.isSpell() && si.getSourceCard() != null ? ids.card(si.getSourceCard().getId())
                        : ids.encode(IdCodec.Kind.STACK, si.getId());
                t.name = sa.getHostCard() == null ? "?" : sa.getHostCard().getName();
            } else {
                continue;
            }
            if (t.id != null) {
                out.add(t);
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ---- Karten ----------------------------------------------------------------------------------------------------------

    public UUID cardId(Card c) {
        return ids.card(c.getId());
    }

    public UUID playerId(Player p) {
        return ids.player(p.getId());
    }

    public UUID entityId(GameEntity e) {
        if (e instanceof Player p) {
            return ids.player(p.getId());
        }
        if (e instanceof Card c) {
            return ids.card(c.getId());
        }
        return null;
    }

    public List<CardDto> cards(Iterable<Card> cards, PlayerView viewer) {
        List<CardDto> out = new ArrayList<>();
        for (Card c : cards) {
            out.add(card(c, viewer));
        }
        return out;
    }

    /** Karte aus Sicht von {@code viewer} (null = Zuschauer); Verborgenes ohne Name/Bild. */
    public CardDto card(Card c, PlayerView viewer) {
        CardDto d = new CardDto();
        fill(d, c, viewer);
        return d;
    }

    private PermanentDto permanent(Card c, PlayerView viewer, Set<Integer> attacking, Set<Integer> blocking) {
        PermanentDto d = new PermanentDto();
        fill(d, c, viewer);
        d.tapped = c.isTapped();
        d.damage = c.getDamage();
        d.sick = c.isCreature() && c.isSick();
        d.phasedOut = c.isPhasedOut();
        d.flipped = c.isFlipped();
        List<UUID> att = new ArrayList<>();
        for (Card a : c.getAttachedCards()) {
            att.add(ids.card(a.getId()));
        }
        d.attachments = att.isEmpty() ? null : att;
        d.attachedTo = entityId(c.getEntityAttachedTo());
        d.attacking = attacking.contains(c.getId());
        d.blocking = blocking.contains(c.getId());
        d.ownerId = c.getOwner() == null ? null : ids.player(c.getOwner().getId());
        d.controllerId = c.getController() == null ? null : ids.player(c.getController().getId());
        d.row = c.isCreature() ? "creature" : c.isLand() ? "land" : "other";
        d.ptModified = !c.isFaceDown() && c.isCreature()
                && (c.getNetPower() != c.getBasePower() || c.getNetToughness() != c.getBaseToughness());
        return d;
    }

    private void fill(CardDto d, Card c, PlayerView viewer) {
        d.id = ids.card(c.getId());
        CardView cv = c.getView();
        boolean visible = viewer == null ? publicVisible(c) : cv.canBeShownTo(viewer);
        boolean faceDown = c.isFaceDown();
        d.faceDown = faceDown || !visible;
        if (!visible) {
            d.name = "";
            return;
        }
        if (faceDown) {
            boolean seeFace = viewer != null && cv.canFaceDownBeShownTo(viewer);
            if (!seeFace) {
                d.name = "";
                d.types = WireNames.types(c.getType());
                if (c.isCreature()) {
                    d.power = String.valueOf(c.getNetPower());
                    d.toughness = String.valueOf(c.getNetToughness());
                }
                d.counters = counters(c.getCounters());
                return;
            }
        }
        d.name = faceDown ? c.getState(CardStateName.Original).getName() : c.getName();
        IPaperCard pc = c.getPaperCard();
        d.set = scryfallSet(pc);
        d.num = number(pc);
        d.token = c.isToken();
        if (c.isToken()) {
            d.image = c.getName();
        }
        d.manaCost = manaCost(c);
        d.mv = c.getCMC();
        String typeLine = c.getType() == null ? null : c.getType().toString();
        d.typeLine = typeLine == null || typeLine.isBlank() ? null : typeLine;
        d.types = WireNames.types(c.getType());
        d.colors = WireNames.colors(c.getColor());
        if (c.isCreature()) {
            d.power = String.valueOf(c.getNetPower());
            d.toughness = String.valueOf(c.getNetToughness());
        }
        if (c.isPlaneswalker()) {
            d.loyalty = String.valueOf(c.getCurrentLoyalty());
        }
        d.rarity = c.getRarity() == null ? null : c.getRarity().name().substring(0, 1);
        d.rules = rules(cv);
        d.counters = counters(c.getCounters());
        d.transformable = c.isDoubleFaced();
        d.transformed = c.isTransformed();
    }

    /** Zuschauer: oeffentliche Zonen, nie verdeckt. */
    private static boolean publicVisible(Card c) {
        if (c.isFaceDown()) {
            return false;
        }
        ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
        return z == null || z == ZoneType.Battlefield || z == ZoneType.Graveyard || z == ZoneType.Exile
                || z == ZoneType.Command || z == ZoneType.Stack;
    }

    private static String manaCost(Card c) {
        if (c.getManaCost() == null || c.getManaCost().isNoCost()) {
            return null;
        }
        String s = c.getManaCost().getSimpleString();
        if (s == null || s.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : s.trim().split("\\s+")) {
            sb.append('{').append(part).append('}');
        }
        return sb.toString();
    }

    private static List<String> rules(CardView cv) {
        String text;
        try {
            text = cv.getText();
        } catch (RuntimeException e) {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<CounterDto> counters(Multiset<CounterType> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        List<CounterDto> out = new ArrayList<>();
        for (Multiset.Entry<CounterType> e : m.entrySet()) {
            if (e.getCount() > 0) {
                out.add(new CounterDto(WireNames.counter(e.getElement()), e.getCount()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Integer> mana(Player p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (dev.magelite.game.ManaColor mc : dev.magelite.game.ManaColor.values()) {
            int n = p.getManaPool().getAmountOfColor(mc.atom);
            if (n > 0) {
                out.put(mc.symbol, n);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Scryfall-Setcode (gross) einer Druckfassung; null wenn unbekannt. */
    public static String scryfallSet(IPaperCard pc) {
        if (pc == null || pc.getEdition() == null) {
            return null;
        }
        CardEdition ed = StaticData.instance().getEditions().get(pc.getEdition());
        String code = ed == null ? pc.getEdition() : ed.getScryfallCode();
        return code == null ? null : code.toUpperCase(java.util.Locale.ROOT);
    }

    public static String number(IPaperCard pc) {
        if (pc == null) {
            return null;
        }
        String n = pc.getCollectorNumber();
        return n == null || n.isBlank() || IPaperCard.NO_COLLECTOR_NUMBER.equals(n) ? null : n;
    }

    // ---- spielbare Objekte ------------------------------------------------------------------------------------------------

    /**
     * Prioritaet: Objekte mit Nicht-Mana-Aktionen (Hand, Spielfeld, Flashback per {@link AvailableActions}, dazu die
     * Kommandozone) und ungetappte Manaquellen.
     */
    public Playable priorityPlayable(Player p, Set<CardView> actionable) {
        Map<UUID, Integer> all = new LinkedHashMap<>();
        Set<UUID> actions = new LinkedHashSet<>();
        for (CardView cv : actionable) {
            UUID id = ids.card(cv.getId());
            actions.add(id);
            all.merge(id, 1, Integer::sum);
        }
        for (UUID id : manaSources(p)) {
            all.merge(id, 1, Integer::sum);
        }
        return new Playable(all, new ArrayList<>(actions));
    }

    /**
     * Forges KI-Pruefung kennt Einberufen/Improvisieren nicht: Zauber mit dem Schlagwort zaehlen als spielbar, wenn
     * ungetappte Manaquellen plus passende Kreaturen/Artefakte die Manakosten erreichen (nur Anzeige, grob).
     */
    private static void addConvoke(Player p, Set<CardView> out) {
        int sources = 0;
        int creatures = 0;
        int artifacts = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped()) {
                continue;
            }
            if (c.isCreature()) {
                creatures++;
            } else if (c.isArtifact()) {
                artifacts++;
            }
            if (!c.getManaAbilities().isEmpty() && !c.isCreature()) {
                sources++;
            }
        }
        for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Command}) {
            for (Card c : p.getCardsIn(z)) {
                boolean convoke = c.hasKeyword(Keyword.CONVOKE);
                boolean improvise = c.hasKeyword(Keyword.IMPROVISE);
                if ((!convoke && !improvise) || out.contains(c.getView())) {
                    continue;
                }
                int helpers = (convoke ? creatures : 0) + (improvise ? artifacts : 0);
                if (sources + helpers >= c.getCMC() && !c.getAllPossibleAbilities(p, true).isEmpty()) {
                    out.add(c.getView());
                }
            }
        }
    }

    /** Bezahlen: nur Manaquellen. */
    public Playable manaPlayable(Player p) {
        Map<UUID, Integer> all = new LinkedHashMap<>();
        for (UUID id : manaSources(p)) {
            all.put(id, 1);
        }
        return new Playable(all, List.of());
    }

    private List<UUID> manaSources(Player p) {
        List<UUID> out = new ArrayList<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Command}) {
            for (Card c : p.getCardsIn(z)) {
                for (SpellAbility sa : c.getManaAbilities()) {
                    sa.setActivatingPlayer(p);
                    if (sa.canPlay()) {
                        out.add(ids.card(c.getId()));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Nicht-Mana-Aktionen eines Spielers (Spiel-Thread; kann dauern, Budget in ms). */
    public static Set<CardView> actionable(Player p, long budgetMs) {
        Set<CardView> out = new LinkedHashSet<>(AvailableActions.collectActionable(p, budgetMs));
        addConvoke(p, out);
        Collection<Card> command = p.getCardsIn(ZoneType.Command);
        if (!command.isEmpty()) {
            p.runWithController(() -> {
                for (Card c : command) {
                    for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                        if (!sa.isManaAbility() && (sa.getPayCosts() == null || !sa.getPayCosts().hasManaCost()
                                || ComputerUtilMana.canPayManaCost(sa, p, 0, false))
                                && ComputerUtilAbility.isFullyTargetable(sa)) {
                            out.add(c.getView());
                            break;
                        }
                    }
                }
            }, new PlayerControllerAi(p.getGame(), p, p.getOriginalLobbyPlayer()));
        }
        return out;
    }
}
