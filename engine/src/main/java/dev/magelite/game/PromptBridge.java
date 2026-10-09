package dev.magelite.game;

import dev.magelite.view.ForgeViewMapper;
import dev.magelite.view.IdCodec;
import dev.magelite.view.RichText;
import dev.magelite.view.dto.PromptDto;
import forge.ai.AiBlockController;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputConfirm;
import forge.gamemodes.match.input.InputConfirmMulligan;
import forge.gamemodes.match.input.InputLondonMulligan;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gamemodes.match.input.InputSelectCardsForConvokeOrImprovise;
import forge.gamemodes.match.input.InputSelectEntitiesFromList;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uebersetzt Forges offene Eingaben (Inputs) und blockierende Dialoge in {@link PromptDto}s und Client-Antworten
 * zurueck in Forge-Aufrufe. Jede Frage ist ein {@link Frame}, den {@link GameHost#park} auf dem Spiel-Thread haelt, bis
 * er erledigt ist. Antworten laufen immer auf dem Spiel-Thread (inbox), nie vom WS-Thread.
 */
final class PromptBridge {

    private static final Logger LOG = Logger.getLogger(PromptBridge.class);

    /** Rechtsklick (Forge: Angreifer/Blocker zuruecknehmen). */
    private static final ITriggerEvent RIGHT_CLICK = new ITriggerEvent() {
        @Override
        public int getButton() {
            return 3;
        }

        @Override
        public int getX() {
            return 0;
        }

        @Override
        public int getY() {
            return 0;
        }
    };

    enum StateMode { NONE, PRIORITY, MANA }

    private final GameHost host;
    /** nicht abgebildete Fragen (automatisch beantwortet), je Methode */
    final Map<String, AtomicInteger> unmapped = new ConcurrentHashMap<>();
    /** bewusst automatisch beantwortete Fragen (Phase 1), je Methode */
    final Map<String, AtomicInteger> autos = new ConcurrentHashMap<>();

    PromptBridge(GameHost host) {
        this.host = host;
    }

    private Game game() {
        return host.game();
    }

    private ForgeViewMapper mapper() {
        return host.mapper();
    }

    private IdCodec ids() {
        return host.mapper().ids();
    }

    /** Laeuft gerade die Einberufen-Auswahl dieses Sitzes? */
    boolean convokeOpen(GameHost.HumanSeat seat) {
        for (Frame f : host.framesView()) {
            if (f.seat == seat && f instanceof InputFrame inf && inf.isConvoke()) {
                return true;
            }
        }
        return false;
    }

    /** F-Taste: offene Prioritaet dieses Sitzes passen. */
    boolean passPriority(GameHost.HumanSeat seat) {
        Frame f = host.topFrame();
        if (f instanceof InputFrame inf && f.seat == seat && inf.input instanceof InputPassPriority && !f.done()) {
            seat.controller().selectButtonOk();
            return true;
        }
        return false;
    }

    void markDirty(GameHost.HumanSeat seat) {
        Frame f = host.topFrame();
        if (f != null && f.seat == seat) {
            f.dirty = true;
        }
    }

    void unmapped(GameHost.HumanSeat seat, String method, String detail) {
        unmapped.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
        LOG.warn("UNMAPPED " + method + " (" + seat.name() + "): " + detail);
    }

    void auto(GameHost.HumanSeat seat, String method, String detail) {
        autos.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
        if (LOG.isDebugEnabled()) {
            LOG.debug("AUTO " + method + " (" + seat.name() + "): " + detail);
        }
    }

    // =====================================================================================================================
    // Frames

    /** Eine offene Frage eines Sitzes. Nur Spiel-Thread. */
    abstract static class Frame {
        final GameHost.HumanSeat seat;
        /** Prompt (neu) senden */
        boolean dirty = true;
        /** Antworten auf diese Frage (Schleifen-Schutz) */
        int answers;

        Frame(GameHost.HumanSeat seat) {
            this.seat = seat;
        }

        abstract boolean done();

        /** Prompt bauen; null = ohne Prompt erledigt bzw. nicht abbildbar (dann {@link #done()} pruefen). */
        abstract PromptDto build();

        abstract void answer(GameHost.Response r);

        /** sichere Standard-Antwort fuer aufgegebene Sitze */
        abstract void autopilot();

        /** Kurzbeschreibung fuer Warnungen */
        String describe() {
            return getClass().getSimpleName();
        }

        StateMode stateMode() {
            return StateMode.NONE;
        }
    }

    private static final java.util.regex.Pattern GOING_FIRST = java.util.regex.Pattern.compile("^(.+?) is going first\\.", java.util.regex.Pattern.MULTILINE);
    private static final java.util.regex.Pattern GOING_POS = java.util.regex.Pattern.compile("you are going (\\d+)");

    /** Forges Starthand-Frage auf Deutsch ("X is going first. Du, you are going 4th. Do you want to keep your hand?") */
    static String mulliganText(String forge) {
        if (forge == null) {
            return null;
        }
        String head;
        java.util.regex.Matcher first = GOING_FIRST.matcher(forge);
        java.util.regex.Matcher pos = GOING_POS.matcher(forge);
        if (forge.contains("you are going first")) {
            head = "Du beginnst.";
        } else if (first.find() && pos.find()) {
            head = first.group(1).trim() + " beginnt, du bist als " + pos.group(1) + ". dran.";
        } else if (forge.contains("keep your hand")) {
            head = "";
        } else {
            return forge;
        }
        return (head.isEmpty() ? "" : head + "\n") + "Starthand behalten?";
    }

    /** Forges Bezahl-Text einzeilig: "Force Spike (222)
Pay Mana Cost: {1}" -> "Force Spike – Mana zahlen: {1}" */
    static String manaText(String forge) {
        if (forge == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String line : forge.split("\r?\n")) {
            String l = dev.magelite.view.ForgeText.clean(line.trim());
            if (!l.isEmpty() && !parts.contains(l)) {
                parts.add(l.replace("Pay Mana Cost:", "Mana zahlen:"));
            }
        }
        return String.join(" – ", parts);
    }

    /** Forges Standardtexte fuer Angriff/Block auf Deutsch; Fehlermeldungen (ungueltige Angriffe/Blocks) bleiben. */
    static String combatText(String forge) {
        if (forge == null) {
            return null;
        }
        String t = forge.trim();
        if (t.startsWith("Select creatures to attack")) {
            return "Angreifer wählen: Kreaturen anklicken, dann bestätigen.";
        }
        if (t.startsWith("Select creatures to block") || t.startsWith("Select another attacker to declare blockers for")) {
            return "Blocker wählen: eigene Kreatur anklicken, dann den Angreifer.";
        }
        return forge;
    }

    private static PromptDto prompt(String kind, String message) {
        PromptDto p = new PromptDto();
        p.kind = kind;
        // feste Forge-Saetze auf Deutsch, Objekt-Nummern/Kontext weg (eigene Texte bleiben unveraendert)
        String text = message == null ? "" : dev.magelite.view.ForgeText.german(message.trim());
        p.message = RichText.parse(text.replace("\n", "<br>"));
        p.messageText = text.replace('\n', ' ');
        return p;
    }

    // ---- Inputs ----------------------------------------------------------------------------------------------------------

    Frame inputFrame(GameHost.HumanSeat seat, CountDownLatch done) {
        Input input = seat.controller().getInputQueue().getInput();
        return new InputFrame(seat, done, input);
    }

    final class InputFrame extends Frame {
        final CountDownLatch latch;
        final Input input;
        /** Angriff/Block in zwei Schritten: gewaehlte eigene Kreatur, Ziel steht noch aus */
        Card pendingCreature;
        List<GameEntity> pendingOptions;
        boolean pendingAlpha;
        boolean autoPayTried;
        /** ungueltige Block-Bestaetigungen in dieser Eingabe */
        int invalidBlocks;

        InputFrame(GameHost.HumanSeat seat, CountDownLatch latch, Input input) {
            super(seat);
            this.latch = latch;
            this.input = input;
        }

        @Override
        boolean done() {
            return latch.getCount() == 0 || game().isGameOver();
        }

        @Override
        String describe() {
            return "InputFrame/" + (input == null ? "null" : input.getClass().getSimpleName()) + " '" + gui().message + "'";
        }

        private SeatGui gui() {
            return seat.gui();
        }

        private HumanController hc() {
            return seat.controller();
        }

        @Override
        StateMode stateMode() {
            if (input instanceof InputPassPriority) {
                return StateMode.PRIORITY;
            }
            if (input instanceof InputPayMana || input instanceof InputSelectCardsForConvokeOrImprovise) {
                return StateMode.MANA;
            }
            return StateMode.NONE;
        }

        boolean isConvoke() {
            return input instanceof InputSelectCardsForConvokeOrImprovise;
        }

        @Override
        PromptDto build() {
            if (input instanceof InputPassPriority) {
                // Forges Text ("Priority: … Turn: … Phase: … Stack: …") wiederholt nur Kopfzeile und Stapel
                var top = game().getStack().peek();
                Card topCard = top == null ? null : top.getSourceCard();
                String topName = topCard == null ? "?" : topCard.isFaceDown() ? "ein verdecktes Objekt" : topCard.getName();
                PromptDto p = prompt("SELECT", top == null ? "Zauber und Fähigkeiten spielen."
                        : "Reagieren oder passen – oben auf dem Stapel: " + topName + ".");
                p.mode = "priority";
                p.stopReason = seat.pass.promptStopReason;
                p.nextStop = seat.pass.promptNextStop;
                return p;
            }
            if (input instanceof InputAttack) {
                return buildAttack();
            }
            if (input instanceof InputBlock) {
                return buildBlock();
            }
            if (input instanceof InputPayMana) {
                return buildMana();
            }
            if (input instanceof InputConfirmMulligan) {
                PromptDto p = prompt("ASK", mulliganText(gui().message));
                p.mulligan = true;
                p.leftBtn = "Mulligan";
                p.rightBtn = "Behalten";
                p.mulligans = seat.mulligans;
                p.freeMulligan = seat.mulligans == 0 && game().getPlayers().size() > 2;
                return p;
            }
            if (input instanceof InputConfirm) {
                PromptDto p = prompt("ASK", gui().message);
                p.leftBtn = gui().btn1;
                p.rightBtn = gui().btn2;
                return p;
            }
            if (input instanceof InputLondonMulligan lm) {
                PromptDto p = prompt("PICK_TARGET", gui().message);
                List<Card> hand = new ArrayList<>(hc().getPlayer().getCardsIn(ZoneType.Hand));
                p.targets = hand.stream().map(mapper()::cardId).toList();
                p.cards = mapper().cards(hand, seat.player().getView());
                p.chosen = lm.getSelectedCards().stream().map(mapper()::cardId).toList();
                p.required = !gui().btn1Enabled;
                return p;
            }
            if (input instanceof InputSelectEntitiesFromList<?> sel) {
                PromptDto p = prompt("PICK_TARGET", gui().message);
                List<UUID> targets = new ArrayList<>();
                List<Card> cards = new ArrayList<>();
                for (GameEntity e : sel.getValidChoices()) {
                    targets.add(mapper().entityId(e));
                    if (e instanceof Card c) {
                        cards.add(c);
                    }
                }
                p.targets = targets;
                p.cards = cards.isEmpty() ? null : mapper().cards(cards, seat.player().getView());
                p.chosen = sel.getSelected().stream().map(e -> mapper().entityId((GameEntity) e)).toList();
                p.required = !gui().btn1Enabled;
                p.rightBtn = gui().btn1Enabled ? "Fertig" : null;
                return p;
            }
            if (input instanceof InputSelectTargets) {
                return buildTargets();
            }
            if (input instanceof InputSelectCardsForConvokeOrImprovise conv) {
                return buildConvoke(conv);
            }
            unmapped(seat, "input:" + (input == null ? "null" : input.getClass().getSimpleName()), gui().message);
            hc().selectButtonOk();
            if (!done()) {
                hc().selectButtonCancel();
            }
            return null;
        }

        @Override
        void answer(GameHost.Response r) {
            if (input instanceof InputPassPriority) {
                if (r.uuid() != null) {
                    Card c = card(r.uuid());
                    if (c == null || !hc().selectCard(c.getView(), null, null)) {
                        host.toast(seat, "info", "Das geht gerade nicht.");
                    }
                } else if (Boolean.FALSE.equals(r.bool())) {
                    String sig = seat.pass.promptSig;
                    if (sig != null) {
                        seat.pass.passedSigs.add(sig); // gleiche Stapelobjekte ab jetzt automatisch passen
                    }
                    hc().selectButtonOk(); // passen
                }
            } else if (input instanceof InputAttack) {
                answerAttack(r);
            } else if (input instanceof InputBlock) {
                answerBlock(r);
            } else if (input instanceof InputPayMana) {
                answerMana(r);
            } else if (input instanceof InputConfirmMulligan) {
                if (Boolean.TRUE.equals(r.bool())) {
                    seat.mulligans++;
                    hc().selectButtonCancel(); // Mulligan
                } else {
                    hc().selectButtonOk(); // behalten
                }
            } else if (input instanceof InputConfirm) {
                if (Boolean.TRUE.equals(r.bool())) {
                    hc().selectButtonOk();
                } else {
                    hc().selectButtonCancel();
                }
            } else if (input instanceof InputLondonMulligan) {
                if (r.uuid() != null) {
                    Card c = card(r.uuid());
                    if (c != null) {
                        hc().selectCard(c.getView(), null, null);
                    }
                } else if (gui().btn1Enabled) {
                    hc().selectButtonOk();
                }
            } else if (input instanceof InputSelectCardsForConvokeOrImprovise conv) {
                answerConvoke(conv, r);
            } else if (input instanceof InputSelectEntitiesFromList<?> || input instanceof InputSelectTargets) {
                if (r.uuid() != null) {
                    PromptDto before = build();
                    select(r.uuid());
                    PromptDto after = done() ? null : build();
                    if (before != null && after != null && java.util.Objects.equals(before.chosen, after.chosen)) {
                        host.toast(seat, "info", "Das geht als Ziel nicht.");
                    }
                } else {
                    okOrCancel();
                }
            }
        }

        @Override
        void autopilot() {
            if (input instanceof InputConfirmMulligan) {
                hc().selectButtonOk(); // Hand behalten
            } else if (input instanceof InputLondonMulligan) {
                hc().selectButtonCancel(); // Forge waehlt selbst
            } else if (input instanceof InputPayMana) {
                if (gui().btn2Enabled) {
                    hc().selectButtonCancel();
                } else {
                    hc().selectButtonOk();
                }
            } else if (input instanceof InputConfirm) {
                hc().selectButtonCancel();
            } else if (input instanceof InputSelectTargets || input instanceof InputSelectEntitiesFromList<?>) {
                if (gui().btn2Enabled) {
                    hc().selectButtonCancel();
                } else if (gui().btn1Enabled) {
                    hc().selectButtonOk();
                } else {
                    // erstes Ziel, das Forge annimmt (eine abgelehnte Option immer wieder zu waehlen, liefe endlos)
                    PromptDto p = build();
                    if (p == null || p.targets == null) {
                        return;
                    }
                    int before = p.chosen == null ? 0 : p.chosen.size();
                    for (UUID id : p.targets) {
                        if (p.chosen != null && p.chosen.contains(id)) {
                            continue;
                        }
                        select(id);
                        if (done() || gui().btn1Enabled) {
                            return;
                        }
                        PromptDto after = build();
                        if (after == null || (after.chosen == null ? 0 : after.chosen.size()) != before) {
                            return;
                        }
                    }
                }
            } else {
                hc().selectButtonOk();
            }
        }

        private void select(UUID id) {
            IdCodec.Decoded d = ids().decode(id);
            if (d == null) {
                return;
            }
            if (d.kind() == IdCodec.Kind.PLAYER) {
                Player p = game().getPlayer(d.id());
                if (p != null) {
                    hc().selectPlayer(p.getView(), null);
                }
            } else if (d.kind() == IdCodec.Kind.CARD) {
                Card c = game().findById(d.id());
                if (c != null) {
                    hc().selectCard(c.getView(), null, null);
                }
            }
        }

        private void okOrCancel() {
            if (gui().btn1Enabled) {
                hc().selectButtonOk();
            } else if (gui().btn2Enabled) {
                hc().selectButtonCancel();
            }
        }

        // ---- Angriff

        private PromptDto buildAttack() {
            Combat combat = hc().combat;
            Player me = hc().combatPlayer;
            if (pendingCreature != null) {
                PromptDto p = prompt("PICK_TARGET", pendingAlpha ? "Wen sollen alle angreifen?" : "Wen soll " + pendingCreature.getName() + " angreifen?");
                p.defenderPick = true;
                p.targets = pendingOptions.stream().map(mapper()::entityId).toList();
                List<Card> cards = pendingOptions.stream().filter(e -> e instanceof Card).map(e -> (Card) e).toList();
                p.cards = cards.isEmpty() ? null : mapper().cards(cards, seat.player().getView());
                return p;
            }
            PromptDto p = prompt("SELECT", combatText(gui().message));
            p.mode = "attackers";
            List<UUID> possible = new ArrayList<>();
            boolean anyAttacking = false;
            if (combat != null && me != null) {
                for (Card c : me.getCreaturesInPlay()) {
                    boolean attacking = combat.isAttacking(c);
                    anyAttacking |= attacking;
                    if (attacking || CombatUtil.canAttack(c)) {
                        possible.add(mapper().cardId(c));
                    }
                }
            }
            p.possibleAttackers = possible;
            p.specialBtn = !anyAttacking && !possible.isEmpty() ? "Alle angreifen" : null;
            return p;
        }

        private void answerAttack(GameHost.Response r) {
            Combat combat = hc().combat;
            Player me = hc().combatPlayer;
            if (combat == null || me == null) {
                hc().selectButtonOk();
                return;
            }
            if (pendingCreature != null) {
                GameEntity def = r.uuid() == null ? null : entity(r.uuid());
                Card creature = pendingCreature;
                boolean alpha = pendingAlpha;
                List<GameEntity> options = pendingOptions;
                pendingCreature = null;
                pendingOptions = null;
                pendingAlpha = false;
                if (def != null && options.contains(def)) {
                    setDefender(def);
                    if (alpha) {
                        hc().alphaStrike();
                    } else {
                        hc().selectCard(creature.getView(), null, null);
                    }
                }
                return;
            }
            if ("special".equals(r.string())) {
                List<GameEntity> defs = new ArrayList<>(combat.getDefenders());
                defs.removeIf(e -> !(e instanceof Player));
                if (defs.size() > 1) {
                    pendingCreature = me.getCreaturesInPlay().isEmpty() ? null : me.getCreaturesInPlay().get(0);
                    if (pendingCreature != null) {
                        pendingOptions = defs;
                        pendingAlpha = true;
                        return;
                    }
                }
                if (!defs.isEmpty()) {
                    setDefender(defs.get(0));
                }
                hc().alphaStrike();
                return;
            }
            if (r.uuid() != null) {
                GameEntity e = entity(r.uuid());
                if (e instanceof Card c && c.getController() == me && c.isCreature()) {
                    if (combat.isAttacking(c)) {
                        hc().selectCard(c.getView(), null, RIGHT_CLICK); // zuruecknehmen
                        return;
                    }
                    List<GameEntity> options = new ArrayList<>();
                    for (GameEntity def : combat.getDefenders()) {
                        if (CombatUtil.canAttack(c, def)) {
                            options.add(def);
                        }
                    }
                    if (options.size() == 1) {
                        setDefender(options.get(0));
                        hc().selectCard(c.getView(), null, null);
                    } else if (options.size() > 1) {
                        pendingCreature = c;
                        pendingOptions = options;
                    } else {
                        host.toast(seat, "info", c.getName() + " kann niemanden angreifen.");
                    }
                    return;
                }
                if (e != null && combat.getDefenders().contains(e)) {
                    setDefender(e);
                }
                return;
            }
            if (!CombatUtil.validateAttackers(combat)) {
                fixAttack(combat);
            }
            hc().selectButtonOk(); // Angriff bestaetigen
        }

        /**
         * Ungueltige Angriffserklaerung (Goad, "muss angreifen", "nicht allein" ...): wie XMage die naechstgelegene
         * gueltige Erklaerung einsetzen, sonst fragt Forge endlos neu.
         */
        private void fixAttack(Combat combat) {
            Map<Card, GameEntity> best = combat.getAttackConstraints().getLegalAttackers().getLeft();
            List<String> changed = new ArrayList<>();
            for (Card a : new ArrayList<>(combat.getAttackers())) {
                if (!best.containsKey(a)) {
                    hc().selectCard(a.getView(), null, RIGHT_CLICK);
                }
            }
            for (Map.Entry<Card, GameEntity> e : best.entrySet()) {
                if (!combat.isAttacking(e.getKey(), e.getValue())) {
                    setDefender(e.getValue());
                    hc().selectCard(e.getKey().getView(), null, null);
                    changed.add(e.getKey().getName());
                }
            }
            LOG.info("Angriff ungueltig - ersetzt durch Forges gueltige Erklaerung (" + seat.name() + "): " + changed);
            host.toast(seat, "info", changed.isEmpty() ? "Angriff angepasst (Angriffs-Pflichten)."
                    : "Muss angreifen: " + String.join(", ", changed));
        }

        private void setDefender(GameEntity def) {
            if (def instanceof Player p) {
                hc().selectPlayer(p.getView(), null);
            } else if (def instanceof Card c) {
                hc().selectCard(c.getView(), null, null);
            }
        }

        // ---- Block

        private PromptDto buildBlock() {
            Combat combat = hc().combat;
            Player me = hc().combatPlayer;
            if (pendingCreature != null) {
                PromptDto p = prompt("PICK_TARGET", "Welchen Angreifer soll " + pendingCreature.getName() + " blocken?");
                p.targets = pendingOptions.stream().map(mapper()::entityId).toList();
                return p;
            }
            PromptDto p = prompt("SELECT", combatText(gui().message));
            p.mode = "blockers";
            List<UUID> possible = new ArrayList<>();
            if (combat != null && me != null) {
                for (Card b : me.getCreaturesInPlay()) {
                    boolean blocking = !combat.getAttackersBlockedBy(b).isEmpty();
                    boolean can = false;
                    if (!blocking && CombatUtil.canBlock(b)) {
                        for (Card a : combat.getAttackers()) {
                            if (CombatUtil.canBlock(a, b, combat)) {
                                can = true;
                                break;
                            }
                        }
                    }
                    if (blocking || can) {
                        possible.add(mapper().cardId(b));
                    }
                }
            }
            p.possibleBlockers = possible;
            return p;
        }

        private void answerBlock(GameHost.Response r) {
            Combat combat = hc().combat;
            Player me = hc().combatPlayer;
            if (combat == null || me == null) {
                hc().selectButtonOk();
                return;
            }
            if (pendingCreature != null) {
                Card blocker = pendingCreature;
                List<GameEntity> options = pendingOptions;
                pendingCreature = null;
                pendingOptions = null;
                GameEntity a = r.uuid() == null ? null : entity(r.uuid());
                if (a instanceof Card attacker && options.contains(attacker)) {
                    hc().selectCard(attacker.getView(), null, null);
                    hc().selectCard(blocker.getView(), null, null);
                }
                return;
            }
            if (r.uuid() != null) {
                GameEntity e = entity(r.uuid());
                if (!(e instanceof Card c)) {
                    return;
                }
                if (c.getController() == me) {
                    if (!combat.getAttackersBlockedBy(c).isEmpty()) {
                        hc().selectCard(c.getView(), null, RIGHT_CLICK); // Block zuruecknehmen
                        return;
                    }
                    List<GameEntity> options = new ArrayList<>();
                    for (Card a : combat.getAttackers()) {
                        if (CombatUtil.canBlock(a, c, combat)) {
                            options.add(a);
                        }
                    }
                    if (options.size() == 1) {
                        hc().selectCard(((Card) options.get(0)).getView(), null, null);
                        hc().selectCard(c.getView(), null, null);
                    } else if (options.size() > 1) {
                        pendingCreature = c;
                        pendingOptions = options;
                    } else {
                        host.toast(seat, "info", c.getName() + " kann nichts blocken.");
                    }
                } else if (combat.isAttacking(c)) {
                    hc().selectCard(c.getView(), null, null);
                }
                return;
            }
            String invalid = CombatUtil.validateBlocks(combat, me);
            if (invalid != null && ++invalidBlocks >= 2) {
                // zweiter ungueltiger Versuch: Forges Block-KI erfuellt die Pflichten (statt endlos neu zu fragen)
                new AiBlockController(me, false).assignBlockersForCombat(combat);
                LOG.info("Blocks ungueltig (" + invalid + ") - Forge-KI blockt fuer " + seat.name());
                host.toast(seat, "info", "Blocker automatisch gesetzt (" + invalid + ").");
            }
            hc().selectButtonOk(); // Blocks bestaetigen (Forge prueft, meldet Fehler per message)
        }

        // ---- Mana

        private PromptDto buildMana() {
            if (!autoPayTried && seat.autoPayDefault() && gui().btn1Enabled) {
                autoPayTried = true;
                hc().selectButtonOk(); // Forges "Auto": KI bezahlt mit passenden Quellen
                if (done()) {
                    return null;
                }
                host.toast(seat, "info", "Automatisches Bezahlen nicht möglich – bitte Manaquellen anklicken.");
            }
            PromptDto p = prompt("PLAY_MANA", manaText(gui().message));
            p.required = !gui().btn2Enabled;
            p.sourceId = stackSource();
            return p;
        }

        private void answerMana(GameHost.Response r) {
            if (r.manaType() != null) {
                hc().useMana(r.manaType().atom);
            } else if (r.uuid() != null) {
                Card c = card(r.uuid());
                if (c != null) {
                    hc().selectCard(c.getView(), null, null);
                }
            } else if (Boolean.FALSE.equals(r.bool())) {
                if (gui().btn2Enabled) {
                    hc().selectButtonCancel();
                } else {
                    host.toast(seat, "info", "Diese Kosten müssen bezahlt werden.");
                }
            } else {
                autoPay();
            }
        }

        void autoPay() {
            if (isConvoke()) {
                hc().selectButtonOk(); // Einberufen fertig; den Rest zahlt das folgende Mana (automatisch)
                return;
            }
            if (gui().btn1Enabled) {
                hc().selectButtonOk();
                if (!done()) {
                    host.toast(seat, "info", "Automatisches Bezahlen nicht möglich – bitte Manaquellen anklicken.");
                }
            } else {
                host.toast(seat, "info", "Automatisches Bezahlen nicht möglich – bitte Manaquellen anklicken.");
            }
        }

        // ---- Einberufen / Improvisieren (Forge fragt vor dem Mana)

        private PromptDto buildConvoke(InputSelectCardsForConvokeOrImprovise conv) {
            String desc = field(conv, "description", String.class);
            // Forges Text ist mehrzeilig (Karte, Typ, Anweisung) und sprengt die Fussleiste
            java.util.regex.Matcher rest = java.util.regex.Pattern.compile("Remaining mana cost is (\\S+?)\\.?\\s*$")
                    .matcher(gui().message == null ? "" : gui().message.trim());
            String open = rest.find() ? " (offen: " + rest.group(1) + ")" : "";
            PromptDto p = prompt("PLAY_MANA", "Improvise".equals(desc) ? "Improvisieren: Artefakte wählen, die mitbezahlen" + open + "."
                    : "Convoke".equals(desc) ? "Einberufen: Kreaturen wählen, die mitbezahlen" + open + "." : gui().message);
            p.specialBtn = "Improvise".equals(desc) ? "Improvisieren" : "Convoke".equals(desc) ? "Einberufen" : desc;
            List<UUID> targets = new ArrayList<>();
            Iterable<?> avail = field(conv, "availableCards", Iterable.class);
            if (avail != null) {
                for (Object o : avail) {
                    if (o instanceof Card c && !conv.getSelected().contains(c)) {
                        targets.add(mapper().cardId(c));
                    }
                }
            }
            p.specialTargets = targets;
            p.sourceId = stackSource();
            return p;
        }

        private void answerConvoke(InputSelectCardsForConvokeOrImprovise conv, GameHost.Response r) {
            if (r.uuid() != null) {
                Card c = card(r.uuid());
                Iterable<?> avail = field(conv, "availableCards", Iterable.class);
                boolean special = false;
                if (c != null && avail != null) {
                    for (Object o : avail) {
                        special |= o == c;
                    }
                }
                if (special) {
                    hc().selectCard(c.getView(), null, null);
                } else {
                    hc().selectButtonOk(); // anderes (Land): Einberufen beenden, normal weiter bezahlen
                }
            } else if (Boolean.FALSE.equals(r.bool())) {
                hc().selectButtonCancel(); // kein Einberufen
            } else {
                hc().selectButtonOk();
            }
        }

        // ---- Makros (Mehrfach-Angriff/-Block, Zuruecksetzen)

        /** Mehrere Kreaturen greifen {@code target} an bzw. blocken den Angreifer {@code target}. */
        void combatMacro(List<UUID> ids, UUID target) {
            Combat combat = hc().combat;
            if (combat == null) {
                return;
            }
            GameEntity t = entity(target);
            List<String> failed = new ArrayList<>();
            if (input instanceof InputAttack) {
                pendingCreature = null;
                pendingOptions = null;
                pendingAlpha = false;
                if (t == null || !combat.getDefenders().contains(t)) {
                    host.toast(seat, "info", "Dieses Ziel kann nicht angegriffen werden.");
                    return;
                }
                setDefender(t);
                for (UUID id : ids) {
                    Card c = card(id);
                    if (c == null || combat.isAttacking(c, t)) {
                        continue;
                    }
                    if (combat.isAttacking(c)) {
                        hc().selectCard(c.getView(), null, RIGHT_CLICK);
                    }
                    if (CombatUtil.canAttack(c, t)) {
                        hc().selectCard(c.getView(), null, null);
                    } else {
                        failed.add(c.getName());
                    }
                }
            } else if (input instanceof InputBlock) {
                pendingCreature = null;
                pendingOptions = null;
                if (!(t instanceof Card attacker) || !combat.isAttacking(attacker)) {
                    return;
                }
                hc().selectCard(attacker.getView(), null, null);
                for (UUID id : ids) {
                    Card b = card(id);
                    if (b == null || combat.isBlocking(b, attacker)) {
                        continue;
                    }
                    if (CombatUtil.canBlock(attacker, b, combat)) {
                        hc().selectCard(b.getView(), null, null);
                    } else {
                        failed.add(b.getName());
                    }
                }
            }
            if (!failed.isEmpty()) {
                host.toast(seat, "info", "Nicht möglich: " + String.join(", ", failed));
            }
        }

        /** "Angriff zuruecksetzen": alle eigenen Angreifer zurueck (bzw. offene Verteidiger-Wahl verwerfen). */
        void combatReset() {
            Combat combat = hc().combat;
            if (!(input instanceof InputAttack) || combat == null) {
                return;
            }
            if (pendingCreature != null) {
                pendingCreature = null;
                pendingOptions = null;
                pendingAlpha = false;
                return;
            }
            if (!combat.getAttackers().isEmpty()) {
                hc().selectButtonCancel(); // Forge "Call Back"; ohne Angreifer waere das "Alle angreifen"
            }
        }

        boolean isCombat(String mode) {
            return "attackers".equals(mode) ? input instanceof InputAttack : input instanceof InputBlock;
        }

        // ---- Ziele

        private PromptDto buildTargets() {
            SpellAbility sa = field(input, "sa", SpellAbility.class);
            // Forges Text ist mehrzeilig (Karte - Beschreibung, Auswahl, "Targeted: ..."); gewaehlte Ziele zeigt die UI selbst
            String msg = gui().message;
            if (sa != null && sa.getHostCard() != null && sa.getTargetRestrictions() != null) {
                Card host = sa.getHostCard();
                msg = (host.isFaceDown() ? "Verdecktes Objekt" : host.getName()) + " – " + sa.getTargetRestrictions().getVTSelection();
            }
            PromptDto p = prompt("PICK_TARGET", msg);
            Set<UUID> targets = new LinkedHashSet<>();
            List<Card> offBoard = new ArrayList<>();
            for (CardView cv : gui().selectablesView()) {
                Card c = game().findById(cv.getId());
                if (c != null) {
                    targets.add(mapper().cardId(c));
                    if (!c.isInZone(ZoneType.Battlefield)) {
                        offBoard.add(c);
                    }
                }
            }
            List<UUID> chosen = new ArrayList<>();
            if (sa != null) {
                for (Player pl : game().getPlayers()) {
                    if (!pl.hasLost() && sa.canTarget(pl)) {
                        targets.add(mapper().playerId(pl));
                    }
                }
                for (GameEntity e : sa.getTargets().getTargetEntities()) {
                    UUID id = mapper().entityId(e);
                    if (id != null) {
                        chosen.add(id);
                    }
                }
                if (sa.getHostCard() != null) {
                    p.sourceId = mapper().cardId(sa.getHostCard());
                }
            }
            p.targets = new ArrayList<>(targets);
            p.cards = offBoard.isEmpty() ? null : mapper().cards(offBoard, seat.player().getView());
            p.chosen = chosen;
            p.required = !gui().btn1Enabled && !gui().btn2Enabled;
            return p;
        }

        private UUID stackSource() {
            var top = game().getStack().peek();
            if (top != null && top.getActivatingPlayer() == seat.player()) {
                if (top.isSpell() && top.getSourceCard() != null) {
                    return mapper().cardId(top.getSourceCard());
                }
                return ids().encode(IdCodec.Kind.STACK, top.getId());
            }
            return null;
        }
    }

    private Card card(UUID id) {
        IdCodec.Decoded d = ids().decode(id);
        return d == null || d.kind() != IdCodec.Kind.CARD ? null : game().findById(d.id());
    }

    private GameEntity entity(UUID id) {
        IdCodec.Decoded d = ids().decode(id);
        if (d == null) {
            return null;
        }
        return switch (d.kind()) {
            case CARD -> game().findById(d.id());
            case PLAYER -> game().getPlayer(d.id());
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name, Class<T> type) {
        if (o == null) {
            return null;
        }
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(o);
                return type.isInstance(v) ? (T) v : null;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException | RuntimeException e) {
                LOG.warn("Feld " + name + " nicht lesbar: " + e);
                return null;
            }
        }
        return null;
    }

    // ---- Dialoge -----------------------------------------------------------------------------------------------------------

    /** Dialog mit Ergebnis; {@code finished} beendet die Park-Schleife. */
    abstract class Dialog<T> extends Frame {
        T result;
        boolean finished;

        Dialog(GameHost.HumanSeat seat) {
            super(seat);
        }

        @Override
        boolean done() {
            return finished || game().isGameOver();
        }

        void finish(T value) {
            result = value;
            finished = true;
        }

        T run(T fallback) {
            host.park(this);
            return finished ? result : fallback;
        }
    }

    SpellAbilityView chooseAbility(GameHost.HumanSeat seat, CardView hostCard, List<SpellAbilityView> abilities) {
        Dialog<SpellAbilityView> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("CHOOSE_ABILITY", "Welche Fähigkeit?");
                List<PromptDto.Item> items = new ArrayList<>();
                for (SpellAbilityView sav : abilities) {
                    String text = sav.getDescription() == null || sav.getDescription().isBlank() ? sav.toString() : sav.getDescription();
                    items.add(new PromptDto.Item(ids().encode(IdCodec.Kind.SA, sav.getId()).toString(), text, null, null, null));
                }
                p.choices = items;
                if (hostCard != null) {
                    Card c = game().findById(hostCard.getId());
                    p.sourceId = c == null ? null : mapper().cardId(c);
                }
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                if (r.uuid() != null) {
                    IdCodec.Decoded dec = ids().decode(r.uuid());
                    for (SpellAbilityView sav : abilities) {
                        if (dec != null && dec.kind() == IdCodec.Kind.SA && sav.getId() == dec.id()) {
                            finish(sav);
                            return;
                        }
                    }
                } else {
                    finish(null);
                }
            }

            @Override
            void autopilot() {
                finish(null);
            }
        };
        return d.run(null);
    }

    Integer amount(GameHost.HumanSeat seat, String message, int min, int max) {
        Dialog<Integer> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("AMOUNT", message);
                p.min = min;
                p.max = max;
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                if (r.integer() != null) {
                    finish(Math.max(min, Math.min(max, r.integer())));
                } else if (r.string() != null) {
                    try {
                        finish(Math.max(min, Math.min(max, Integer.parseInt(r.string().trim()))));
                    } catch (NumberFormatException ignored) {
                        // erneut fragen
                    }
                }
            }

            @Override
            void autopilot() {
                finish(min);
            }
        };
        return d.run(min);
    }

    boolean ask(GameHost.HumanSeat seat, String message, String yes, String no, boolean defaultYes) {
        Dialog<Boolean> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("ASK", message);
                p.leftBtn = yes;
                p.rightBtn = no;
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                if (r.bool() != null) {
                    finish(r.bool());
                }
            }

            @Override
            void autopilot() {
                finish(false);
            }
        };
        return Boolean.TRUE.equals(d.run(defaultYes));
    }

    /**
     * Reihenfolge festlegen: nacheinander "was zuerst?" (Faehigkeiten als PICK_ABILITY, Karten als PICK_TARGET, sonst
     * CHOOSE_CHOICE); "Fertig"/Abbrechen uebernimmt den Rest in der angebotenen Reihenfolge.
     */
    <T> List<T> order(GameHost.HumanSeat seat, String title, String top, List<T> items) {
        List<T> remaining = new ArrayList<>(items);
        List<T> out = new ArrayList<>();
        while (remaining.size() > 1 && !game().isGameOver()) {
            String msg = (title == null ? "Reihenfolge" : title) + (top == null || top.isBlank() ? "" : " – " + top)
                    + " (" + (out.size() + 1) + "/" + items.size() + ")";
            T pick;
            boolean abilities = remaining.stream().allMatch(x -> x instanceof SpellAbilityView || x instanceof SpellAbility);
            boolean cards = remaining.stream().allMatch(x -> x instanceof CardView cv && game().findById(cv.getId()) != null);
            if (abilities) {
                pick = pickOption(seat, "PICK_ABILITY", msg, remaining, null, true);
            } else if (cards) {
                @SuppressWarnings("unchecked")
                List<GameEntityView> views = (List<GameEntityView>) (List<?>) remaining;
                List<GameEntityView> one = entities(seat, msg, views, 0, 1);
                @SuppressWarnings("unchecked")
                T t = one.isEmpty() ? null : (T) one.get(0);
                pick = t;
            } else {
                List<T> one = choices(seat, msg, 0, 1, remaining, null);
                pick = one.isEmpty() ? null : one.get(0);
            }
            if (pick == null) {
                break;
            }
            out.add(pick);
            remaining.remove(pick);
        }
        out.addAll(remaining);
        return out;
    }

    /** Eine Option aus Faehigkeiten/Modi waehlen (Items mit OPTION-ids); null = abgebrochen. */
    private <T> T pickOption(GameHost.HumanSeat seat, String kind, String message, List<T> options,
                             FSerializableFunction<T, String> display, boolean optional) {
        Dialog<T> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt(kind, message);
                List<PromptDto.Item> items = new ArrayList<>();
                for (int i = 0; i < options.size(); i++) {
                    T o = options.get(i);
                    String text = display != null ? display.apply(o) : o instanceof SpellAbilityView sav
                            ? (sav.getDescription() == null || sav.getDescription().isBlank() ? sav.toString() : sav.getDescription())
                            : String.valueOf(o);
                    UUID src = null;
                    if (o instanceof SpellAbilityView sav && sav.getHostCard() != null) {
                        Card c = game().findById(sav.getHostCard().getId());
                        src = c == null ? null : mapper().cardId(c);
                    } else if (o instanceof SpellAbility sa && sa.getHostCard() != null) {
                        src = mapper().cardId(sa.getHostCard());
                    }
                    items.add(new PromptDto.Item(ids().encode(IdCodec.Kind.OPTION, i).toString(), text, src, null, null));
                }
                p.choices = items;
                p.required = !optional;
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                if (r.uuid() != null) {
                    IdCodec.Decoded dec = ids().decode(r.uuid());
                    if (dec != null && dec.kind() == IdCodec.Kind.OPTION && dec.id() >= 0 && dec.id() < options.size()) {
                        finish(options.get(dec.id()));
                    }
                } else if (optional) {
                    finish(null);
                }
            }

            @Override
            void autopilot() {
                finish(optional ? null : options.get(0));
            }
        };
        return d.run(optional ? null : options.get(0));
    }

    /**
     * Ersatzeffekt-Wahl als CHOOSE_CHOICE (Schluessel = Index) mit Gruppen nach Regeltext; die UI zeigt sie als
     * Ersatzeffekt-Dialog ({@link GameHost#replacement}).
     */
    int replacement(GameHost.HumanSeat seat, List<forge.game.replacement.ReplacementEffect> effects, List<PromptDto.ReplGroup> groups) {
        Dialog<Integer> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("CHOOSE_CHOICE", "Welcher Ersatzeffekt zuerst?");
                PromptDto.ChoiceDto c = new PromptDto.ChoiceDto();
                c.message = p.messageText;
                c.required = true;
                c.keyed = true;
                List<PromptDto.ChoiceItem> items = new ArrayList<>();
                for (int i = 0; i < effects.size(); i++) {
                    var re = effects.get(i);
                    String host = re.getHostCard() == null ? "?" : re.getHostCard().getName();
                    items.add(new PromptDto.ChoiceItem(String.valueOf(i), host + ": " + ReplacementAssist.rule(re), null, null));
                }
                c.items = items;
                c.groups = groups;
                p.choice = c;
                p.required = true;
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                try {
                    int i = Integer.parseInt(r.string() == null ? "" : r.string().trim());
                    if (i >= 0 && i < effects.size()) {
                        finish(i);
                    }
                } catch (NumberFormatException ignored) {
                    // erneut fragen
                }
            }

            @Override
            void autopilot() {
                finish(0);
            }
        };
        return d.run(0);
    }

    /** Stapel waehlen: true = Stapel 1. */
    boolean pile(GameHost.HumanSeat seat, SpellAbility sa, Iterable<Card> pile1, Iterable<Card> pile2) {
        Dialog<Boolean> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("CHOOSE_PILE", "Wähle einen Stapel");
                p.pile1 = mapper().cards(pile1, seat.player().getView());
                p.pile2 = mapper().cards(pile2, seat.player().getView());
                if (sa != null && sa.getHostCard() != null) {
                    p.sourceId = mapper().cardId(sa.getHostCard());
                }
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                if (r.bool() != null) {
                    finish(r.bool());
                }
            }

            @Override
            void autopilot() {
                finish(true);
            }
        };
        return Boolean.TRUE.equals(d.run(true));
    }

    /**
     * Auswahl aus einer Liste: Spiel-Objekte als PICK_TARGET (nur wenn alle im Spiel aufloesbar sind), sonst
     * CHOOSE_CHOICE (Index-Schluessel).
     */
    @SuppressWarnings("unchecked")
    <T> List<T> choices(GameHost.HumanSeat seat, String message, int min, int max, List<T> choices, FSerializableFunction<T, String> display) {
        boolean entities = choices.stream().allMatch(c -> (c instanceof CardView cv && game().findById(cv.getId()) != null)
                || (c instanceof PlayerView pv && game().getPlayer(pv) != null));
        if (entities) {
            List<GameEntityView> views = (List<GameEntityView>) (List<?>) choices;
            return (List<T>) (List<?>) entities(seat, message, views, min, max);
        }
        boolean modes = choices.stream().allMatch(c -> c instanceof SpellAbility || c instanceof SpellAbilityView);
        if (modes && max == 1) {
            T pick = pickOption(seat, "CHOOSE_MODE", message, choices, display, min == 0);
            return pick == null ? new ArrayList<>() : new ArrayList<>(List.of(pick));
        }
        boolean cardNames = choices.stream().allMatch(c -> c instanceof forge.game.card.CardFaceView || c instanceof forge.card.ICardFace);
        List<T> selected = new ArrayList<>();
        Dialog<List<T>> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("CHOOSE_CHOICE", message);
                PromptDto.ChoiceDto c = new PromptDto.ChoiceDto();
                c.message = p.messageText;
                c.required = selected.size() < min;
                c.keyed = true;
                if (max > 1) {
                    c.subMessage = selected.size() + "/" + max + " gewählt";
                }
                List<PromptDto.ChoiceItem> items = new ArrayList<>();
                for (int i = 0; i < choices.size(); i++) {
                    T t = choices.get(i);
                    if (selected.contains(t)) {
                        continue;
                    }
                    String text = display != null ? display.apply(t) : String.valueOf(t);
                    items.add(new PromptDto.ChoiceItem(String.valueOf(i), text, null, null));
                }
                c.items = items;
                c.manaColor = choices.stream().allMatch(x -> x instanceof String s && isColorName(s));
                if (cardNames) {
                    c.search = true;
                    c.hint = "card";
                }
                p.choice = c;
                p.required = c.required;
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                String key = r.string();
                if (key != null && !key.isEmpty()) {
                    try {
                        int i = Integer.parseInt(key);
                        if (i >= 0 && i < choices.size() && !selected.contains(choices.get(i))) {
                            selected.add(choices.get(i));
                        }
                    } catch (NumberFormatException e) {
                        for (T t : choices) {
                            String text = display != null ? display.apply(t) : String.valueOf(t);
                            if (key.equalsIgnoreCase(text) && !selected.contains(t)) {
                                selected.add(t);
                                break;
                            }
                        }
                    }
                    if (selected.size() >= max || selected.size() == choices.size()) {
                        finish(new ArrayList<>(selected));
                    }
                } else if (selected.size() >= min) {
                    finish(new ArrayList<>(selected));
                }
            }

            @Override
            void autopilot() {
                finish(new ArrayList<>(choices.subList(0, Math.min(min, choices.size()))));
            }
        };
        return d.run(new ArrayList<>(choices.subList(0, Math.min(min, choices.size()))));
    }

    private static boolean isColorName(String s) {
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "white", "blue", "black", "red", "green", "colorless" -> true;
            default -> false;
        };
    }

    /** Spiel-Objekte waehlen (Karten/Spieler) als PICK_TARGET; Mehrfachwahl per Klick-Folge + "Fertig". */
    List<GameEntityView> entities(GameHost.HumanSeat seat, String message, List<? extends GameEntityView> options, int min, int max) {
        Map<UUID, GameEntityView> byId = new LinkedHashMap<>();
        List<Card> cards = new ArrayList<>();
        for (GameEntityView v : options) {
            if (v instanceof CardView cv) {
                Card c = game().findById(cv.getId());
                if (c != null) {
                    byId.put(mapper().cardId(c), v);
                    cards.add(c);
                }
            } else if (v instanceof PlayerView pv) {
                Player p = game().getPlayer(pv);
                if (p != null) {
                    byId.put(mapper().playerId(p), v);
                }
            }
        }
        List<GameEntityView> chosen = new ArrayList<>();
        int realMax = Math.max(1, Math.min(max, byId.size()));
        Dialog<List<GameEntityView>> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("PICK_TARGET", message);
                p.targets = new ArrayList<>(byId.keySet());
                p.cards = cards.isEmpty() ? null : mapper().cards(cards, seat.player().getView());
                List<UUID> ch = new ArrayList<>();
                for (Map.Entry<UUID, GameEntityView> e : byId.entrySet()) {
                    if (chosen.contains(e.getValue())) {
                        ch.add(e.getKey());
                    }
                }
                p.chosen = ch;
                p.required = chosen.size() < min;
                p.rightBtn = chosen.size() >= min && realMax > 1 ? "Fertig" : null;
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                if (r.uuid() != null) {
                    GameEntityView v = byId.get(r.uuid());
                    if (v == null) {
                        return;
                    }
                    if (chosen.contains(v)) {
                        chosen.remove(v);
                    } else if (chosen.size() < realMax) {
                        chosen.add(v);
                    }
                    if (realMax == 1 && chosen.size() == 1) {
                        finish(new ArrayList<>(chosen));
                    }
                } else if (chosen.size() >= min) {
                    finish(new ArrayList<>(chosen));
                }
            }

            @Override
            void autopilot() {
                List<GameEntityView> out = new ArrayList<>(byId.values());
                finish(new ArrayList<>(out.subList(0, Math.min(min, out.size()))));
            }
        };
        List<GameEntityView> all = new ArrayList<>(byId.values());
        return d.run(new ArrayList<>(all.subList(0, Math.min(min, all.size()))));
    }

    /** Kampfschaden verteilen: Vorschlag = toedlicher Schaden der Reihe nach, Rest auf den Verteidiger bzw. den letzten Blocker. */
    Map<CardView, Integer> combatDamage(GameHost.HumanSeat seat, CardView attacker, List<CardView> blockers, int damage, GameEntityView defender) {
        List<String> labels = new ArrayList<>();
        List<Integer> defaults = new ArrayList<>();
        int left = damage;
        for (int i = 0; i < blockers.size(); i++) {
            CardView b = blockers.get(i);
            int lethal = Math.max(0, b.getLethalDamage());
            int give = Math.min(left, lethal);
            if (defender == null && i == blockers.size() - 1) {
                give = left;
            }
            labels.add(b.getName());
            defaults.add(give);
            left -= give;
        }
        if (defender != null) {
            labels.add(defender.toString());
            defaults.add(left);
        }
        List<Integer> amounts = multiAmount(seat, "Kampfschaden von " + attacker.getName() + " verteilen (" + damage + ")",
                labels, defaults, damage, false);
        Map<CardView, Integer> out = new HashMap<>();
        for (int i = 0; i < blockers.size(); i++) {
            out.put(blockers.get(i), amounts.get(i));
        }
        if (defender != null) {
            out.put(null, amounts.get(blockers.size()));
        }
        return out;
    }

    Map<Object, Integer> genericAmount(GameHost.HumanSeat seat, CardView source, Map<Object, Integer> target, int amount, boolean atLeastOne, String label) {
        List<Object> keys = new ArrayList<>(target.keySet());
        List<String> labels = new ArrayList<>();
        List<Integer> defaults = new ArrayList<>();
        int left = amount;
        for (Object k : keys) {
            labels.add(String.valueOf(k));
            int give = atLeastOne ? Math.min(1, left) : 0;
            defaults.add(give);
            left -= give;
        }
        if (!defaults.isEmpty()) {
            defaults.set(0, defaults.get(0) + left);
        }
        List<Integer> amounts = multiAmount(seat, (source == null ? "" : source.getName() + ": ") + amount + " " + label + " verteilen",
                labels, defaults, amount, atLeastOne);
        Map<Object, Integer> out = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            out.put(keys.get(i), amounts.get(i));
        }
        return out;
    }

    private List<Integer> multiAmount(GameHost.HumanSeat seat, String message, List<String> labels, List<Integer> defaults, int total, boolean atLeastOne) {
        Dialog<List<Integer>> d = new Dialog<>(seat) {
            @Override
            PromptDto build() {
                PromptDto p = prompt("MULTI_AMOUNT", message);
                p.min = total;
                p.max = total;
                List<PromptDto.AmountItem> items = new ArrayList<>();
                for (int i = 0; i < labels.size(); i++) {
                    items.add(new PromptDto.AmountItem(labels.get(i), atLeastOne ? 1 : 0, total, defaults.get(i)));
                }
                p.items = items;
                return p;
            }

            @Override
            void answer(GameHost.Response r) {
                if (r.string() == null) {
                    return;
                }
                String[] parts = r.string().trim().split("\\s+");
                if (parts.length != labels.size()) {
                    return;
                }
                List<Integer> out = new ArrayList<>();
                int sum = 0;
                for (String s : parts) {
                    try {
                        int v = Integer.parseInt(s);
                        if (v < (atLeastOne ? 1 : 0)) {
                            return;
                        }
                        out.add(v);
                        sum += v;
                    } catch (NumberFormatException e) {
                        return;
                    }
                }
                if (sum == total) {
                    finish(out);
                } else {
                    host.toast(seat, "info", "Die Summe muss " + total + " sein.");
                }
            }

            @Override
            void autopilot() {
                finish(new ArrayList<>(defaults));
            }
        };
        return d.run(new ArrayList<>(defaults));
    }

    /** Debug-Hilfe fuer Spikes: wie oft nicht abgebildet bzw. automatisch beantwortet wurde. */
    Map<String, Integer> unmappedCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        unmapped.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    Map<String, Integer> autoCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        autos.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
