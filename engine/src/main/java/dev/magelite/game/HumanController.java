package dev.magelite.game;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;

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
        if (host.policy().autoPass(seat, this)) {
            return null;
        }
        return super.chooseSpellAbilityToPlay();
    }

    @Override
    public void declareAttackers(Player attackingPlayer, Combat combat) {
        host.safePoint();
        if (seat.conceded()) {
            return;
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
        this.combat = combat;
        this.combatPlayer = defender;
        try {
            super.declareBlockers(defender, combat);
        } finally {
            this.combat = null;
            this.combatPlayer = null;
        }
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
