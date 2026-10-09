package dev.magelite.game;

import forge.LobbyPlayer;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.cost.CostPart;
import forge.game.GameEntity;
import forge.game.replacement.ReplacementEffect;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;

import java.util.List;

/**
 * Forge-Controller eines menschlichen Sitzes. Haengt an den Entscheidungs-Toren MageLites Politik ein: Safe-Point
 * (inbox), Autopilot nach Aufgabe, Auto-Passen ({@link AutoPassPolicy}); merkt sich den Kampf fuer die Prompts.
 */
public final class HumanController extends PlayerControllerHuman {

    private final GameHost host;
    private final GameHost.HumanSeat seat;
    /** laufende Angriffs-/Block-Erklaerung (Spiel-Thread) */
    Combat combat;
    Player combatPlayer;
    private boolean scenarioMulliganDone;

    HumanController(Game game, Player player, LobbyPlayer lobby, GameHost host, GameHost.HumanSeat seat) {
        super(game, player, lobby);
        this.host = host;
        this.seat = seat;
    }

    GameHost.HumanSeat seat() {
        return seat;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        host.safePoint();
        if (seat.conceded() || getGame().isGameOver()) {
            return null;
        }
        // eine Ersatzeffekt-Entscheidung gilt nur fuer das laufende Ereignis
        seat.replMacro = null;
        seat.replConfirm = null;
        GameHost.RepeatMacro rm = seat.repeat;
        if (rm != null) {
            SpellAbility next = nextRepeat(rm);
            if (next != null) {
                return List.of(next);
            }
        }
        if (host.policy().autoPass(seat, this)) {
            return null;
        }
        return super.chooseSpellAbilityToPlay();
    }

    /**
     * "N-mal aktivieren": dieselbe Faehigkeit erneut, solange nur eigene Objekte auf dem Stapel liegen und sie
     * aktivierbar ist. Forge gibt dem Aktivierenden die Prioritaet zurueck, ein Halten ist nicht noetig.
     */
    private SpellAbility nextRepeat(GameHost.RepeatMacro rm) {
        if (rm.remaining <= 0) {
            seat.repeat = null;
            return null;
        }
        for (var si : getGame().getStack()) {
            if (si.getActivatingPlayer() != getPlayer()) {
                stopRepeat("Mehrfach-Aktivierung angehalten – ein Gegner hat reagiert.");
                return null;
            }
        }
        Card source = getGame().findById(rm.sourceCardId);
        SpellAbility pick = null;
        if (source != null) {
            for (SpellAbility sa : source.getAllPossibleAbilities(getPlayer(), true)) {
                if (sa.getId() == rm.abilityId) {
                    pick = sa;
                    break;
                }
            }
            if (pick == null) {
                for (SpellAbility sa : source.getAllPossibleAbilities(getPlayer(), true)) {
                    if (rm.abilityText != null && rm.abilityText.equals(sa.getDescription())) {
                        pick = sa;
                        break;
                    }
                }
            }
        }
        if (pick == null) {
            stopRepeat("Mehrfach-Aktivierung beendet – Fähigkeit nicht mehr aktivierbar.");
            return null;
        }
        rm.remaining--; // bei 0 endet das Makro erst an der naechsten Prioritaet (Forge fragt beim Ausspielen nochmal)
        return pick;
    }

    void stopRepeat(String msg) {
        if (seat.repeat != null) {
            seat.repeat = null;
            host.toast(seat, "info", msg);
        }
    }

    /** gerade gespielte, selbst gewaehlte Faehigkeit (Kosten-Bestaetigungen dafuer entfallen) */
    private SpellAbility playing;

    @Override
    public boolean playChosenSpellAbility(SpellAbility chosenSa) {
        SpellAbility prev = playing;
        playing = chosenSa;
        try {
            return super.playChosenSpellAbility(chosenSa);
        } finally {
            playing = prev;
        }
    }

    /**
     * "Do you want to pay 1 life?" & Co.: fuer Kosten des gerade selbst gewirkten Zaubers bzw. der selbst aktivierten
     * Faehigkeit nicht nachfragen (wie XMage) - der Klick war die Entscheidung. Kosten aus Effekten ("es sei denn, du
     * zahlst ...") fragen weiter.
     */
    @Override
    public boolean confirmPayment(CostPart costPart, String question, SpellAbility sa) {
        if (playing != null && sa != null && sa.getRootAbility() == playing.getRootAbility()) {
            return true;
        }
        return super.confirmPayment(costPart, question, sa);
    }

    /** Eine einzelne "wiederholbare" Faehigkeit trotzdem zur Wahl stellen (Knopf "N-mal aktivieren" in der UI). */
    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        boolean force = abilities.size() == 1 && repeatable(abilities.get(0)) && seat.repeat == null;
        seat.gui().forceAbilityChoice = force;
        try {
            return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
        } finally {
            seat.gui().forceAbilityChoice = false;
        }
    }

    /** aktiviert, kein Mana, keine Ziele, kein Tappen: sinnvoll mehrfach hintereinander (Necropotence & Co.) */
    static boolean repeatable(SpellAbility sa) {
        return sa.isActivatedAbility() && !sa.isManaAbility() && !sa.usesTargeting()
                && (sa.getPayCosts() == null || (!sa.getPayCosts().hasManaCost() && !sa.getPayCosts().hasTapCost()));
    }

    /** Farbe beim Einberufen: die erste, die Forge als bezahlbar anbietet (keine Frage, wie bei XMage). */
    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        if (host.bridge().convokeOpen(seat) && colors != null && colors.getColor() != 0) {
            return (byte) Integer.lowestOneBit(colors.getColor() & 0xff);
        }
        return super.chooseColorAllowColorless(message, c, colors);
    }

    @Override
    public void declareAttackers(Player attackingPlayer, Combat combat) {
        host.safePoint();
        if (seat.conceded()) {
            return;
        }
        if (AutoPassPolicy.skipsOwnAttack(seat.pass, getGame(), attackingPlayer)
                && forge.game.combat.CombatUtil.validateAttackers(combat)) {
            return; // F9/F11: kein Angriff, solange kein Angriffszwang besteht (wie XMage)
        }
        this.combat = combat;
        this.combatPlayer = attackingPlayer;
        try {
            super.declareAttackers(attackingPlayer, combat);
        } finally {
            this.combat = null;
            this.combatPlayer = null;
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        host.safePoint();
        if (seat.conceded()) {
            return;
        }
        if (!canBlockAny(defender, combat)) {
            return; // nichts kann blocken: keine Frage (wie XMage)
        }
        this.combat = combat;
        this.combatPlayer = defender;
        try {
            super.declareBlockers(defender, combat);
        } finally {
            this.combat = null;
            this.combatPlayer = null;
        }
    }

    private static boolean canBlockAny(Player defender, Combat combat) {
        for (Card b : defender.getCreaturesInPlay()) {
            if (!CombatUtil.canBlock(b)) {
                continue;
            }
            for (Card a : combat.getAttackers()) {
                if (CombatUtil.canBlock(a, b, combat)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mulliganKeepHand(Player player, int cardsToReturn) {
        if (seat.conceded()) {
            return true;
        }
        ScenarioHooks sc = host.scenario();
        if (sc != null && !scenarioMulliganDone) {
            scenarioMulliganDone = true;
            sc.beforeMulligan(host, getPlayer());
        }
        return super.mulliganKeepHand(player, cardsToReturn);
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        ScenarioHooks sc = host.scenario();
        Player p = sc == null ? null : sc.startingPlayer(host);
        return p != null ? p : getPlayer(); // wie bei XMage: keine Frage, wer beginnt
    }

    /**
     * Starthand-Aktionen (Leylines, Gemstone Caverns ...) je Karte als Ja/Nein-Frage wie bei XMage
     * ("Put X onto the battlefield?"), statt Forges Mehrfachauswahl.
     */
    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usable) {
        List<SpellAbility> out = new java.util.ArrayList<>();
        for (SpellAbility sa : usable) {
            Card c = sa.getHostCard();
            String name = c == null ? "?" : c.getName();
            String desc = sa.getDescription() == null ? "" : sa.getDescription();
            String q = desc.toLowerCase(java.util.Locale.ROOT).contains("battlefield") ? "Put " + name + " onto the battlefield?"
                    : "Use " + name + " from your opening hand?";
            if (host.bridge().ask(seat, q, "Yes", "No", true)) {
                out.add(sa);
            }
        }
        return out;
    }

    /**
     * Ersatzeffekt waehlen: gleiche Effekte als Gruppe (CHOOSE_CHOICE mit {@code choice.groups}); abgelehnte Effekte
     * (laufende Entscheidung oder "fuer dieses Spiel merken") waehlt die Engine selbst und verneint die Rueckfrage.
     */
    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possible) {
        if (possible.size() == 1) {
            return possible.get(0);
        }
        java.util.List<dev.magelite.view.dto.PromptDto.ReplGroup> groups = ReplacementAssist.groups(possible, host::wireCard);
        GameHost.ReplMacro m = seat.replMacro;
        for (int i = 0; i < possible.size(); i++) {
            ReplacementEffect re = possible.get(i);
            String rule = ReplacementAssist.rule(re);
            boolean declined = (m != null && !m.accept && m.rules.contains(rule)) || seat.replDeclineAlways.containsKey(rule);
            if (declined && ReplacementAssist.optional(re)) {
                seat.replConfirm = Boolean.FALSE;
                return re;
            }
        }
        seat.replMacro = null;
        if (ReplacementAssist.allIdentical(groups)) {
            return possible.get(0);
        }
        int idx = host.bridge().replacement(seat, possible, groups);
        return possible.get(Math.max(0, Math.min(possible.size() - 1, idx)));
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect re, SpellAbility effectSA, GameEntity affected, String question) {
        Boolean preset = seat.replConfirm;
        if (preset != null) {
            seat.replConfirm = null;
            return preset;
        }
        if (seat.replDeclineAlways.containsKey(ReplacementAssist.rule(re))) {
            return false;
        }
        return super.confirmReplacementEffect(re, effectSA, affected, question);
    }

    /** Stapel waehlen (Fact or Fiction & Co.) als CHOOSE_PILE statt Forges Liste mit Pseudo-Karten. */
    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        if (!"True".equals(faceUp)) {
            tempShowCards(pile1);
            tempShowCards(pile2);
        }
        try {
            return host.bridge().pile(seat, sa, pile1, pile2);
        } finally {
            endTempShowCards();
        }
    }

    /** Lobby-Spieler, der fuer einen Sitz den {@link HumanController} erzeugt. */
    static final class Lobby extends LobbyPlayerHuman {
        private final GameHost host;
        private final GameHost.HumanSeat seat;

        Lobby(String name, GameHost host, GameHost.HumanSeat seat) {
            super(name);
            this.host = host;
            this.seat = seat;
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player player = new Player(getName(), game, id);
            HumanController controller = new HumanController(game, player, this, host, seat);
            player.setFirstController(controller);
            seat.bind(player, controller);
            return player;
        }
    }
}
