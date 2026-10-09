package dev.magelite.game;

import com.google.common.eventbus.Subscribe;
import dev.magelite.stats.StatsSink;
import dev.magelite.view.ForgeViewMapper;
import dev.magelite.view.WireNames;
import dev.magelite.view.dto.CardDto;
import dev.magelite.view.dto.Messages;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardForetold;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forge-Spielereignisse ({@code game.subscribeToEvents}) → FX-Ereignisse fuer die UI ({@link GameHost#onFx}) und
 * Statistik ({@code StatsSink}). Ersetzt XMages FxWatcher/StatsWatcher.
 * <p>
 * Handler laufen synchron auf dem Spiel-Thread; sie beobachten nur (nie fragen, blockieren oder das Spiel aendern).
 * Ein einziger Einstiegspunkt ({@link #on}) fuer alle Ereignisse, damit "ausstehende" Entscheidungen (Ziehen, verdecktes
 * Exil) vor dem naechsten Ereignis in fester Reihenfolge abgeschlossen werden (Guava ordnet mehrere Handler je Ereignis
 * nicht).
 * <p>
 * Zwei Forge-Eigenheiten bestimmen das Design:
 * <ul>
 * <li>{@code GameEventCardChangeZone} feuert NACH dem Zonenwechsel, aber VOR {@code Card.setDrawnThisTurn} (Ziehen) und
 * VOR {@code turnFaceDown} (verdecktes Exil: Foretell, Necropotence, Hideaway ...). Beides entscheidet sich erst spaeter
 * ({@link #pendingDraws}, {@link #pendingExiles}); verdecktes Exil wird nie sofort, sondern erst an einem Sicherungspunkt
 * (Stapel-Aufloesung, Prioritaet, Phasenwechsel ...) gemeldet.</li>
 * <li>Zonenereignisse tragen nur {@link CardView}s; die echte Karte (Name, Bild, Verdeckt-Status) kommt per
 * {@code game.findById}.</li>
 * </ul>
 */
final class ForgeEvents {

    private static final Logger LOG = Logger.getLogger(ForgeEvents.class);
    private static final int MAX_ERROR_LOGS = 5;
    /** Ereignisse, die ein Zieh-Kandidat auf das Setzen von {@code drawnThisTurn} warten darf */
    private static final int DRAW_PATIENCE = 4;

    private final GameHost host;

    /** Menschliche Sitze mit Statistik (Spiel-Thread, beim ersten Ereignis; die Sinks entstehen nach dem Abonnieren) */
    private List<Tracked> tracked;

    /** Bibliothek → Hand eines Menschen; gezogen ist die Karte erst, wenn {@code drawnThisTurn} gesetzt ist */
    private final List<PendingDraw> pendingDraws = new ArrayList<>();
    /** Hand/Bibliothek → Exil; ob verdeckt, steht erst nach dem Effekt fest */
    private final List<PendingExile> pendingExiles = new ArrayList<>();
    /** Schaden an Spieler (ohne Gift), dessen Lebensverlust noch kommt: Forge buendelt ihn je Spieler in einem Ereignis */
    private final Map<Integer, Integer> pendingDamage = new HashMap<>();
    /** Karte (id), deren {@code SpellResolved} gerade kam; ihr Stapel→Friedhof-Wechsel folgt direkt ("resolved") */
    private int resolvedCardId = -1;
    private int errors;

    ForgeEvents(GameHost host) {
        this.host = host;
    }

    /** Ein menschlicher Sitz mit {@link StatsSink}. */
    private record Tracked(StatsSink sink, Player player, UUID wireId) {
    }

    private static final class PendingDraw {
        final Tracked who;
        final int cardId;
        final String name;
        int patience = DRAW_PATIENCE;

        PendingDraw(Tracked who, int cardId, String name) {
            this.who = who;
            this.cardId = cardId;
            this.name = name;
        }
    }

    private record PendingExile(int cardId, CardView view, ZoneType from, UUID controllerId, UUID ownerId, UUID sourceId,
                                String sourceName, boolean token, long ts) {
    }

    @Subscribe
    public void on(GameEvent e) {
        if (!host.isGameThread()) {
            return; // kopierte Spiele (KI-Simulation) und fremde Threads ignorieren
        }
        try {
            settle(e);
            if (e instanceof GameEventCardChangeZone z) {
                zoneChange(z);
            } else if (e instanceof GameEventPlayerDamaged d) {
                playerDamaged(d);
            } else if (e instanceof GameEventCardDamaged d) {
                cardDamaged(d);
            } else if (e instanceof GameEventPlayerLivesChanged l) {
                livesChanged(l);
            } else if (e instanceof GameEventCardCounters c) {
                cardCounters(c);
            } else if (e instanceof GameEventPlayerCounters c) {
                playerCounters(c);
            } else if (e instanceof GameEventSpellResolved r) {
                spellResolved(r);
            } else if (e instanceof GameEventSpellAbilityCast c) {
                spellCast(c);
            } else if (e instanceof GameEventLandPlayed l) {
                landPlayed(l);
            } else if (e instanceof GameEventTurnBegan t) {
                turnBegan(t);
            } else if (e instanceof GameEventTurnPhase) {
                pendingDamage.clear(); // nicht verrechneter Schaden (z.B. verhinderter Lebensverlust) verfaellt mit der Phase
            }
        } catch (RuntimeException ex) {
            if (errors++ < MAX_ERROR_LOGS) {
                LOG.warn("ForgeEvents: " + e.getClass().getSimpleName() + ": " + ex, ex);
            }
        }
    }

    // ------------------------------------------------------------------ ausstehende Entscheidungen

    /** Vor jedem Ereignis: Zieh-Kandidaten pruefen; an Sicherungspunkten verdecktes Exil abschliessen. */
    private void settle(GameEvent e) {
        if (!pendingDraws.isEmpty()) {
            Game game = host.game();
            for (Iterator<PendingDraw> it = pendingDraws.iterator(); it.hasNext(); ) {
                PendingDraw p = it.next();
                Card c = game.findById(p.cardId);
                if (c != null && c.getDrawnThisTurn()) {
                    p.who.sink.drew(p.name);
                    it.remove();
                } else if (c == null || --p.patience < 0) {
                    it.remove(); // kein Ziehen (Tutor, Mulligan ...)
                }
            }
        }
        if (!pendingExiles.isEmpty() && isSafePoint(e)) {
            flushExiles();
        }
    }

    /** Foretell/Hideaway/Necropotence & Co. setzen "face down" nach dem Zonenwechsel, aber vor diesen Ereignissen. */
    private static boolean isSafePoint(GameEvent e) {
        return e instanceof GameEventSpellResolved || e instanceof GameEventCardForetold || e instanceof GameEventPlayerPriority
                || e instanceof GameEventTurnPhase || e instanceof GameEventTurnBegan || e instanceof GameEventGameFinished;
    }

    private void flushExiles() {
        List<PendingExile> list = new ArrayList<>(pendingExiles);
        pendingExiles.clear();
        Game game = host.game();
        for (PendingExile p : list) {
            Card c = game.findById(p.cardId);
            if (c == null) {
                continue; // verschwunden (Token): nichts zu zeigen
            }
            // verdeckt, solange die Karte noch im Exil liegt und umgedreht/vorausgesagt ist; zog sie schon weiter (Hand,
            // Bibliothek ...), im Zweifel verdecken
            boolean stillExiled = c.getZone() != null && c.getZone().getZoneType() == ZoneType.Exile;
            boolean hidden = c.isFaceDown() || c.isForetold() || !stillExiled;
            emitZone("exiled", c, p.view, p.cardId, p.from, ZoneType.Exile, hidden, p.controllerId, p.ownerId, p.sourceId,
                    p.sourceName, p.token, p.ts);
        }
    }

    // ------------------------------------------------------------------ Zonenwechsel (FX + Ziehen)

    private void zoneChange(GameEventCardChangeZone ev) {
        CardView cv = ev.card();
        if (cv == null) {
            return;
        }
        ZoneType from = ev.from() == null ? null : ev.from().zoneType();
        ZoneType to = ev.to() == null ? null : ev.to().zoneType();
        boolean resolved = resolvedCardId == cv.getId();
        resolvedCardId = -1; // gilt nur fuer den unmittelbar folgenden Zonenwechsel
        if (from == null || to == null) {
            return;
        }
        if (from == ZoneType.Library && to == ZoneType.Hand) {
            candidateDraw(ev.to().player(), cv);
        }
        if (from == to || host.game().getAge().ordinal() <= GameStage.Mulligan.ordinal()) {
            return; // Mulligan (Hand zurueck in die Bibliothek, Karten nach unten) ist kein Spielereignis
        }
        String kind = kindOf(cv, from, to, resolved);
        if (kind == null) {
            return; // Ziehen, Ausspielen, Stapel→Spielfeld usw.: zeigt der State selbst
        }
        long ts = System.currentTimeMillis();
        UUID owner = playerId(cv.getOwner());
        UUID controller = playerId(cv.getController());
        Card src = resolvingHost();
        UUID sourceId = src == null || src.getId() == cv.getId() ? null : host.mapper().cardId(src);
        String sourceName = sourceId == null ? null : nameOf(src);
        boolean secret = from == ZoneType.Hand || from == ZoneType.Library;
        if ("exiled".equals(kind) && secret) {
            // ob verdeckt, weiss Forge erst nach dem Effekt (turnFaceDown): am naechsten Sicherungspunkt melden
            pendingExiles.add(new PendingExile(cv.getId(), cv, from, controller, owner, sourceId, sourceName, cv.isToken(), ts));
            return;
        }
        // Hand/Bibliothek -> Hand/Bibliothek (Brainstorm, Mulligan ...) verraet sonst die Karte
        boolean hidden = secret && (to == ZoneType.Library || to == ZoneType.Hand);
        Card c = host.game().findById(cv.getId());
        if (c != null && c.isFaceDown()) {
            hidden = true;
        }
        if (c == null && (secret || hidden)) {
            return;
        }
        emitZone(kind, c, cv, cv.getId(), from, to, hidden, controller, owner, sourceId, sourceName, cv.isToken(), ts);
    }

    private String kindOf(CardView cv, ZoneType from, ZoneType to, boolean resolved) {
        if (from == ZoneType.Battlefield && to == ZoneType.Graveyard) {
            return cv.isToken() ? "tokenDied" : "died";
        }
        if (to == ZoneType.Exile) {
            return "exiled";
        }
        if (from == ZoneType.Battlefield && to == ZoneType.Hand) {
            return "bounced";
        }
        if (to == ZoneType.Library) {
            return "tucked";
        }
        if (from == ZoneType.Hand && to == ZoneType.Graveyard) {
            return "discarded";
        }
        if (from == ZoneType.Library && to == ZoneType.Graveyard) {
            return "milled";
        }
        if (from == ZoneType.Stack && to == ZoneType.Graveyard) {
            return resolved ? "resolved" : "countered"; // ohne SpellResolved davor: neutralisiert (auch Fizzle)
        }
        if (from == ZoneType.Stack && to == ZoneType.Command) {
            return resolved ? null : "countered"; // Commander neutralisiert: Ersatzeffekt schickt ihn in die Kommandozone
        }
        if (from == ZoneType.Battlefield && to == ZoneType.Command) {
            return "command";
        }
        return null;
    }

    private void emitZone(String kind, Card c, CardView cv, int cardId, ZoneType from, ZoneType to, boolean hidden,
                          UUID controller, UUID owner, UUID sourceId, String sourceName, boolean token, long ts) {
        String name = null;
        CardDto card = null;
        if (!hidden) {
            // Token existieren ausserhalb des Spielfelds nicht (Zone.add): dann dient die letzte bekannte Fassung (LKI)
            Card shown = c != null ? c : lastKnown(cardId);
            if (shown != null) {
                name = shown.getName();
                card = cardDto(shown);
            } else {
                name = nameOf(cv);
            }
        }
        host.onFx(new Messages.FxEvent(kind, host.mapper().ids().card(cardId), name, card, WireNames.zone(from),
                WireNames.zone(to), controller != null ? controller : owner, owner, sourceId, sourceName, null,
                token ? true : null, null, hidden ? true : null, ts));
    }

    /** Letzte bekannte Fassung einer Karte, die dieses Zug das Spielfeld verlassen hat (Forge legt sie vor dem Ereignis ab). */
    private Card lastKnown(int cardId) {
        List<Card> left = host.game().getLeftBattlefieldThisTurn();
        for (int i = left.size() - 1; i >= 0; i--) {
            if (left.get(i).getId() == cardId) {
                return left.get(i);
            }
        }
        return null;
    }

    /** Karte fuer die Geisterkarte: wie im State, nur ohne Regeltext/Marken/Ziele. */
    private CardDto cardDto(Card c) {
        ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
        boolean publicZone = z == null || z == ZoneType.Battlefield || z == ZoneType.Graveyard || z == ZoneType.Exile
                || z == ZoneType.Command || z == ZoneType.Stack;
        if (publicZone && !c.isFaceDown()) {
            CardDto d = host.mapper().card(c, null);
            d.rules = null;
            d.counters = null;
            d.targets = null;
            d.targetRefs = null;
            d.back = null;
            return d;
        }
        // Hand/Bibliothek (zurueckgekehrte Karte, deren Name oeffentlich ist): der Mapper wuerde sie als verdeckt melden
        CardDto d = new CardDto();
        d.id = host.mapper().ids().card(c.getId());
        d.name = c.getName();
        IPaperCard pc = c.getPaperCard();
        d.set = ForgeViewMapper.scryfallSet(pc);
        d.num = ForgeViewMapper.number(pc);
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
        d.rarity = c.getRarity() == null ? null : c.getRarity().name().substring(0, 1);
        return d;
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

    private void candidateDraw(PlayerView handOwner, CardView cv) {
        Tracked t = trackedOf(handOwner);
        if (t == null) {
            return;
        }
        Card c = host.game().findById(cv.getId());
        String name = c == null ? null : statName(c);
        if (name != null) {
            pendingDraws.add(new PendingDraw(t, cv.getId(), name));
        }
    }

    // ------------------------------------------------------------------ Stapel

    private void spellResolved(GameEventSpellResolved ev) {
        SpellAbilityView sa = ev.spell();
        if (sa != null && sa.isSpell() && sa.getHostCard() != null && !ev.hasFizzled()) {
            resolvedCardId = sa.getHostCard().getId();
        }
    }

    private void spellCast(GameEventSpellAbilityCast ev) {
        SpellAbilityView sa = ev.sa();
        if (sa == null || !sa.isSpell() || sa.getHostCard() == null) {
            return;
        }
        StackItemView si = ev.si();
        Tracked t = trackedOf(si == null ? null : si.getActivatingPlayer());
        if (t == null) {
            return;
        }
        Card c = host.game().findById(sa.getHostCard().getId());
        if (c == null || c.isCopiedSpell()) {
            return; // Kopien (Sturm ...) sind kein Wirken
        }
        boolean commander = c.isCommander() && c.getCastFrom() != null && c.getCastFrom().getZoneType() == ZoneType.Command
                && c.getOwner() != null && c.getOwner().getId() == t.player.getId();
        t.sink.cast(statName(c), host.game().getPhaseHandler().getTurn(), commander);
    }

    private void landPlayed(GameEventLandPlayed ev) {
        Tracked t = trackedOf(ev.player());
        if (t == null || ev.land() == null) {
            return;
        }
        Card c = host.game().findById(ev.land().getId());
        String name = c != null ? statName(c) : ev.land().getOracleName();
        if (name != null) {
            t.sink.landPlayed(name);
        }
    }

    private void turnBegan(GameEventTurnBegan ev) {
        for (Tracked t : tracked()) {
            if (!t.sink.openingRecorded()) {
                List<String> names = new ArrayList<>();
                for (Card c : t.player.getCardsIn(ZoneType.Hand)) {
                    names.add(statName(c));
                }
                t.sink.opening(names);
                t.sink.life(t.player.getLife());
            }
            if (ev.turnOwner() != null && ev.turnOwner().getId() == t.player.getId()) {
                t.sink.humanTurn();
            }
        }
    }

    // ------------------------------------------------------------------ Schaden, Leben, Marken

    private void playerDamaged(GameEventPlayerDamaged ev) {
        int amount = ev.amount();
        PlayerView target = ev.target();
        CardView src = ev.source();
        if (amount <= 0 || target == null) {
            return;
        }
        UUID targetId = playerId(target);
        if (!ev.infect()) {
            pendingDamage.merge(target.getId(), amount, Integer::sum);
        }
        host.onFx(new Messages.FxEvent("damage", null, null, null, null, null, targetId, null, cardId(src), nameOf(src), amount,
                null, ev.combat() ? true : null, null, System.currentTimeMillis()));
        // Statistik: Schaden, den ein Mensch an Fremden anrichtet (byCommander: sein eigener Commander ist die Quelle)
        if (src == null) {
            return;
        }
        PlayerView ctrl = src.getController() != null ? src.getController() : src.getOwner();
        Tracked t = trackedOf(ctrl);
        if (t != null && target.getId() != t.player.getId()) {
            boolean byCommander = src.isCommander() && src.getOwner() != null && src.getOwner().getId() == t.player.getId();
            t.sink.damage(amount, byCommander);
        }
    }

    private void cardDamaged(GameEventCardDamaged ev) {
        CardView cv = ev.card();
        int amount = ev.amount();
        if (cv == null || amount <= 0) {
            return;
        }
        CardView src = ev.source();
        host.onFx(new Messages.FxEvent("damage", host.mapper().ids().card(cv.getId()), nameOf(cv), null, null, null,
                playerId(cv.getController() != null ? cv.getController() : cv.getOwner()), null, cardId(src), nameOf(src),
                amount, null, inCombatDamage() ? true : null, null, System.currentTimeMillis()));
    }

    private void livesChanged(GameEventPlayerLivesChanged ev) {
        PlayerView pv = ev.player();
        if (pv == null) {
            return;
        }
        Tracked t = trackedOf(pv);
        if (t != null) {
            t.sink.life(ev.newLives());
        }
        int delta = ev.newLives() - ev.oldLives();
        if (delta < 0) {
            // Lebensverlust durch Schaden steckt schon im Schadens-Ereignis (Forge: eine Summe je Spieler)
            int pending = pendingDamage.getOrDefault(pv.getId(), 0);
            if (pending > 0) {
                int used = Math.min(pending, -delta);
                if (used == pending) {
                    pendingDamage.remove(pv.getId());
                } else {
                    pendingDamage.put(pv.getId(), pending - used);
                }
                delta += used;
            }
        }
        if (delta == 0) {
            return;
        }
        Card src = resolvingHost();
        host.onFx(new Messages.FxEvent("life", null, null, null, null, null, playerId(pv), null,
                src == null ? null : host.mapper().cardId(src), src == null ? null : nameOf(src), delta, null, null, null,
                System.currentTimeMillis()));
    }

    private void cardCounters(GameEventCardCounters ev) {
        CardView cv = ev.card();
        int delta = ev.newValue() - ev.oldValue();
        if (cv == null || ev.type() == null || delta <= 0 || cv.getZone() != ZoneType.Battlefield) {
            return; // nur Marken auf dem Spielfeld und nur neue (Entfernen, z.B. Loyalitaetskosten, waere Rauschen)
        }
        Card src = resolvingHost();
        host.onFx(new Messages.FxEvent("counter", host.mapper().ids().card(cv.getId()), WireNames.counter(ev.type()), null, null,
                null, playerId(cv.getController() != null ? cv.getController() : cv.getOwner()), null,
                src == null || src.getId() == cv.getId() ? null : host.mapper().cardId(src), nameOf(cv), delta, null, null, null,
                System.currentTimeMillis()));
    }

    private void playerCounters(GameEventPlayerCounters ev) {
        // Forge meldet hier den NEUEN Stand in "amount" (Player.setCounters), nicht die Aenderung; type == null = Neuaufbau
        int delta = ev.amount() - ev.oldValue();
        PlayerView pv = ev.receiver();
        if (pv == null || ev.type() == null || delta <= 0) {
            return;
        }
        host.onFx(new Messages.FxEvent("counter", null, WireNames.counter(ev.type()), null, null, null, playerId(pv), null,
                null, pv.getName(), delta, null, null, null, System.currentTimeMillis()));
    }

    /** Kampfschaden: Phase Kampfschaden und kein Stapelobjekt in Aufloesung (Forge-Ereignis hat kein Kampf-Flag fuer Karten). */
    private boolean inCombatDamage() {
        Game g = host.game();
        PhaseType ph = g.getPhaseHandler().getPhase();
        return (ph == PhaseType.COMBAT_DAMAGE || ph == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) && !g.getStack().isResolving();
    }

    /** Hostkarte des gerade aufloesenden Stapelobjekts (Quelle von Leben, Marken, Neutralisieren); sonst null. */
    private Card resolvingHost() {
        MagicStack stack = host.game().getStack();
        if (!stack.isResolving() || stack.isEmpty()) {
            return null;
        }
        SpellAbility sa = stack.peekAbility();
        return sa == null ? null : sa.getHostCard();
    }

    // ------------------------------------------------------------------ Hilfen

    private List<Tracked> tracked() {
        if (tracked == null) {
            List<Tracked> list = new ArrayList<>();
            for (GameHost.HumanSeat seat : host.seats()) {
                StatsSink sink = StatsSink.of(host.getId(), seat.playerId());
                if (sink != null && seat.player() != null) {
                    list.add(new Tracked(sink, seat.player(), seat.playerId()));
                }
            }
            tracked = list;
        }
        return tracked;
    }

    private Tracked trackedOf(PlayerView pv) {
        if (pv == null) {
            return null;
        }
        for (Tracked t : tracked()) {
            if (t.player.getId() == pv.getId()) {
                return t;
            }
        }
        return null;
    }

    private UUID playerId(PlayerView pv) {
        return pv == null ? null : host.mapper().ids().player(pv.getId());
    }

    private UUID cardId(CardView cv) {
        return cv == null ? null : host.mapper().ids().card(cv.getId());
    }

    /** Oeffentlicher Name; null bei verdeckten Karten (nie den Namen unter einer verdeckten Karte verraten). */
    private static String nameOf(CardView cv) {
        if (cv == null || cv.isFaceDown()) {
            return null;
        }
        String n = cv.getOracleName();
        return n == null || n.isBlank() ? null : n;
    }

    private static String nameOf(Card c) {
        return c == null || c.isFaceDown() ? null : c.getName();
    }

    /** Statistik-Name: der Kartenname der Druckfassung (bleibt in jeder Zone und bei Abenteuer/Verdeckt gleich). */
    private static String statName(Card c) {
        IPaperCard pc = c.getPaperCard();
        return pc != null ? pc.getName() : c.getName();
    }
}
