package dev.magelite.game;

import dev.magelite.view.ForgeViewMapper;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wann ein menschlicher Sitz automatisch passt. Forges eigene Auto-Pass-/Yield-Logik bleibt aus; dieselben Regeln wie
 * zu XMage-Zeiten (HumanSettings, NextStop, TargetCheck, StackSig):
 * <ol>
 *   <li>{@code stopReason}: ein fremdes Stapelobjekt visiert mich oder meins an (je Objekt einmal), bzw. Upkeep eines
 *       Gegners (wenn eingestellt, je Zug einmal) – hält immer und beendet F-Tasten-Passen.</li>
 *   <li>F-Tasten ({@link SkipMode}) bis zu ihrem Ziel.</li>
 *   <li>Eigener Zauber/eigene aktivierte Fähigkeit oben auf dem Stapel → einmal passen (Gegner dürfen antworten),
 *       außer der Sitz hält die Priorität (Mehrfach-Aktivierung).</li>
 *   <li>Gleiches Stapelobjekt wie eines, auf das schon gepasst wurde ({@link #sig}) → passen.</li>
 *   <li>Leerer Stapel: Stopps nur in den eigenen Hauptphasen und in der Endphase der Gegner.</li>
 *   <li>Auto-Passen: keine Nicht-Mana-Aktion → passen (nie in den eigenen Hauptphasen).</li>
 * </ol>
 */
final class AutoPassPolicy {

    /** F-Tasten-Passen. {@code stopOnStack}: ein fremdes Stapelobjekt beendet es. */
    enum SkipMode {
        NONE(null, false),
        /** F5: bis zur Endphase dieses Zugs */
        END_OF_TURN("endOfTurn", true),
        /** F4: bis zum nächsten Zug */
        NEXT_TURN("nextTurn", true),
        /** F6: bis zum nächsten Zug, auch über Stapelobjekte hinweg */
        NEXT_TURN_SKIP_STACK("nextTurn", false),
        /** F7: bis zur nächsten Hauptphase */
        NEXT_MAIN("nextMain", true),
        /** F9: bis zu meinem nächsten Zug */
        MY_TURN("myTurn", false),
        /** F10: bis der Stapel leer ist */
        STACK_RESOLVED("stackResolved", false),
        /** F11: bis zur Endphase vor meinem Zug */
        END_STEP_BEFORE_MY_TURN("endStepBeforeMyTurn", true);

        /** Name in {@code PlayerDto.skips} */
        final String wire;
        final boolean stopOnStack;

        SkipMode(String wire, boolean stopOnStack) {
            this.wire = wire;
            this.stopOnStack = stopOnStack;
        }

        static SkipMode ofAction(String action) {
            return switch (action) {
                case "PASS_PRIORITY_UNTIL_TURN_END_STEP" -> END_OF_TURN;
                case "PASS_PRIORITY_UNTIL_NEXT_TURN" -> NEXT_TURN;
                case "PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK" -> NEXT_TURN_SKIP_STACK;
                case "PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE" -> NEXT_MAIN;
                case "PASS_PRIORITY_UNTIL_MY_NEXT_TURN" -> MY_TURN;
                case "PASS_PRIORITY_UNTIL_STACK_RESOLVED" -> STACK_RESOLVED;
                case "PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN" -> END_STEP_BEFORE_MY_TURN;
                default -> null;
            };
        }
    }

    /** Passen-Zustand eines Sitzes. Nicht-volatile Felder nur auf dem Spiel-Thread. */
    static final class SeatPass {
        volatile SkipMode skip = SkipMode.NONE;
        int skipTurn;
        PhaseType skipPhase;
        volatile boolean stopOppUpkeep;
        volatile boolean stopOnTargeted = true;
        /** Priorität halten (eigene Stapelobjekte nicht automatisch weitergeben) */
        volatile boolean hold;
        final Set<String> passedSigs = new HashSet<>();
        final Set<Integer> targetAlerted = new HashSet<>();
        int upkeepStoppedTurn = -1;
        /** eigenes Stapelobjekt, auf das schon automatisch gepasst wurde */
        int ownTopPassed = -1;
        /** Aktionen der letzten Prüfung, für den folgenden State */
        Set<CardView> cachedActions;
        // Zusätze für den nächsten Prioritäts-Prompt
        String promptStopReason;
        String promptNextStop;
        String promptSig;
    }

    AutoPassPolicy() {
    }

    /**
     * Spiel-Thread, am Prioritäts-Tor. true = automatisch passen; sonst sind die Prompt-Zusätze gesetzt.
     */
    boolean autoPass(GameHost.HumanSeat seat, HumanController c) {
        SeatPass sp = seat.pass;
        Player me = c.getPlayer();
        Game game = c.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        PhaseType phase = ph.getPhase();
        boolean myTurn = ph.getPlayerTurn() == me;
        boolean empty = game.getStack().isEmpty();
        sp.promptStopReason = null;
        sp.promptNextStop = null;
        sp.promptSig = null;
        if (empty) {
            sp.passedSigs.clear();
            sp.targetAlerted.clear();
        }
        Set<CardView> actions = ForgeViewMapper.actionable(me, budgetMs(me));
        sp.cachedActions = actions;

        String reason = stopReason(sp, game, me);
        if (reason != null) {
            sp.skip = SkipMode.NONE;
            sp.promptStopReason = reason;
            return false;
        }
        if (skipActive(sp, game, me)) {
            return true;
        }
        SpellAbilityStackInstance top = game.getStack().peek();
        if (top != null && top.getActivatingPlayer() == me && !top.isTrigger() && !sp.hold && sp.ownTopPassed != top.getId()) {
            sp.ownTopPassed = top.getId();
            return true; // nach eigenem Zauber/eigener Aktivierung weitergeben
        }
        String sig = top == null ? null : sig(top);
        if (sig != null && sp.passedSigs.contains(sig)) {
            return true;
        }
        boolean ownMain = myTurn && empty && (phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2);
        if (empty && !isStop(myTurn, phase)) {
            return true;
        }
        if (seat.autoPass() && actions.isEmpty() && !ownMain) {
            return true;
        }
        sp.promptSig = sig;
        if (myTurn && empty) {
            sp.promptNextStop = nextStop(phase, me, seat.autoPass());
        }
        return false;
    }

    /** Leerer Stapel: wo der Mensch Priorität bekommt (eigene Hauptphasen, Endphase der Gegner). */
    private static boolean isStop(boolean myTurn, PhaseType phase) {
        if (myTurn) {
            return phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2;
        }
        return phase == PhaseType.END_OF_TURN;
    }

    /** F-Taste starten (Spiel-Thread). */
    void startSkip(SeatPass sp, SkipMode mode, Game game) {
        sp.skip = mode == null ? SkipMode.NONE : mode;
        sp.skipTurn = game.getPhaseHandler().getTurn();
        sp.skipPhase = game.getPhaseHandler().getPhase();
    }

    /** Läuft ein F-Tasten-Passen noch? Beendet es, wenn sein Ziel erreicht ist. */
    /**
     * F9/F11 aktiv: den eigenen Angriff ueberspringen (wie XMage bei passedAllTurns/passedUntilEndStepBeforeMyTurn);
     * F4/F5/F7 halten dort weiter an.
     */
    static boolean skipsOwnAttack(SeatPass sp, Game game, Player me) {
        SkipMode m = sp.skip;
        return (m == SkipMode.MY_TURN || m == SkipMode.END_STEP_BEFORE_MY_TURN) && skipActive(sp, game, me);
    }

    private static boolean skipActive(SeatPass sp, Game game, Player me) {
        SkipMode m = sp.skip;
        if (m == SkipMode.NONE) {
            return false;
        }
        PhaseHandler ph = game.getPhaseHandler();
        int turn = ph.getTurn();
        PhaseType phase = ph.getPhase();
        boolean myTurn = ph.getPlayerTurn() == me;
        boolean empty = game.getStack().isEmpty();
        boolean reached = switch (m) {
            case STACK_RESOLVED -> empty;
            case END_OF_TURN -> turn != sp.skipTurn || phase == PhaseType.END_OF_TURN || phase == PhaseType.CLEANUP;
            case NEXT_TURN, NEXT_TURN_SKIP_STACK -> turn != sp.skipTurn;
            case NEXT_MAIN -> (phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2) && (turn != sp.skipTurn || phase != sp.skipPhase);
            case MY_TURN -> myTurn && turn != sp.skipTurn;
            case END_STEP_BEFORE_MY_TURN -> (myTurn && turn != sp.skipTurn)
                    || (phase == PhaseType.END_OF_TURN && game.getNextPlayerAfter(ph.getPlayerTurn()) == me);
            default -> true;
        };
        if (!reached && m.stopOnStack && !empty) {
            SpellAbilityStackInstance top = game.getStack().peek();
            reached = top != null && top.getActivatingPlayer() != me;
        }
        if (reached) {
            sp.skip = SkipMode.NONE;
            return false;
        }
        return true;
    }

    /**
     * Grund, trotz Auto-Passen/F-Tasten anzuhalten (merkt sich den Halt): fremdes Stapelobjekt mit Ziel auf mich bzw.
     * meins, oder Upkeep eines Gegners.
     */
    private static String stopReason(SeatPass sp, Game game, Player me) {
        if (sp.stopOnTargeted) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (si.getActivatingPlayer() == me || sp.targetAlerted.contains(si.getId())) {
                    continue;
                }
                String hit = targetsMine(si.getTargetChoices(), me);
                if (hit != null) {
                    sp.targetAlerted.add(si.getId());
                    Card src = si.getSourceCard();
                    return (src == null ? "?" : src.getName()) + " → " + hit;
                }
            }
        }
        PhaseHandler ph = game.getPhaseHandler();
        if (sp.stopOppUpkeep && ph.getPhase() == PhaseType.UPKEEP && game.getStack().isEmpty() && ph.getPlayerTurn() != me
                && sp.upkeepStoppedTurn != ph.getTurn()) {
            sp.upkeepStoppedTurn = ph.getTurn();
            return "Upkeep von " + ph.getPlayerTurn().getName();
        }
        return null;
    }

    /** Name meines Ziels (ich, meine Permanents/Stapelobjekte/Karten), sonst null. */
    private static String targetsMine(TargetChoices tc, Player me) {
        if (tc == null) {
            return null;
        }
        for (GameObject o : tc) {
            if (o == me) {
                return "dich";
            }
            if (o instanceof Card c) {
                boolean inPlay = c.isInZone(ZoneType.Battlefield) || c.isInZone(ZoneType.Stack);
                if ((inPlay ? c.getController() : c.getOwner()) == me) {
                    return c.getName();
                }
            }
            if (o instanceof SpellAbility sa && sa.getActivatingPlayer() == me) {
                return sa.getHostCard() == null ? "deinen Zauber" : sa.getHostCard().getName();
            }
        }
        return null;
    }

    /**
     * Signatur eines Stapelobjekts (gleiche Trigger von Kopien erkennen): Controller, Quellname, Text, Ziele – ohne
     * Quell-id. Zauber bekommen null (immer einzeln entscheiden).
     */
    static String sig(SpellAbilityStackInstance si) {
        if (si.isSpell()) {
            return null;
        }
        try {
            List<String> targets = new ArrayList<>();
            TargetChoices tc = si.getTargetChoices();
            if (tc != null) {
                for (GameObject o : tc) {
                    targets.add(o instanceof Card c ? "c" + c.getId() : o instanceof Player p ? "p" + p.getId() : String.valueOf(o));
                }
            }
            Collections.sort(targets);
            Card src = si.getSourceCard();
            return (si.getActivatingPlayer() == null ? "" : si.getActivatingPlayer().getId()) + "|" + (src == null ? "" : src.getName())
                    + "|" + si.getStackDescription() + "|" + String.join(",", targets);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Wohin "Weiter" im eigenen Zug bei leerem Stapel führt: main1 | combat | main2 | end, oder null. */
    static String nextStop(PhaseType phase, Player me, boolean autoPass) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case UNTAP, UPKEEP, DRAW -> "main1";
            case MAIN1, COMBAT_BEGIN -> !autoPass || hasAttackers(me) ? "combat" : "main2";
            case COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, COMBAT_FIRST_STRIKE_DAMAGE, COMBAT_DAMAGE -> autoPass ? "main2" : null;
            case COMBAT_END -> "main2";
            default -> "end";
        };
    }

    private static boolean hasAttackers(Player me) {
        try {
            for (Card c : me.getCreaturesInPlay()) {
                if (CombatUtil.canAttack(c)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Laufende F-Taste als {@code PlayerDto.skips} (null = keine). */
    static List<String> skips(SeatPass sp) {
        SkipMode m = sp.skip;
        return m == SkipMode.NONE || m.wire == null ? null : List.of(m.wire);
    }

    /** Wie Forge: 50 ms je Karte in Hand/Spielfeld, 50..1500 ms. */
    static long budgetMs(Player p) {
        int n = p.getCardsIn(ZoneType.Hand).size() + p.getCardsIn(ZoneType.Battlefield).size();
        return Math.min(1500L, Math.max(50L, 50L * n));
    }
}
