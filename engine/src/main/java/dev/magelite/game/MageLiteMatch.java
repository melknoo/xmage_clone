package dev.magelite.game;

import mage.constants.MatchTimeLimit;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.CommanderFreeForAll;
import mage.game.GameException;
import mage.game.match.MatchImpl;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;

/**
 * Commander Free-For-All-Match (4 Spieler, 40 Leben) mit zaehlendem London-Mulligan.
 * Entspricht {@code mage.game.CommanderFreeForAllMatch}, aber ohne Server.
 */
public class MageLiteMatch extends MatchImpl {

    public static final String GAME_TYPE = "Commander Free For All";
    public static final String DECK_TYPE = "Variant Magic - Commander";

    private TrackingLondonMulligan mulligan;

    public MageLiteMatch(MatchOptions options) {
        super(options);
    }

    public static MatchOptions defaultOptions(String name) {
        MatchOptions o = new MatchOptions(name, GAME_TYPE, true);
        o.setDeckType(DECK_TYPE);
        o.setAttackOption(MultiplayerAttackOption.MULTIPLE);
        o.setRange(RangeOfInfluence.ALL);
        o.setWinsNeeded(1);
        o.setFreeMulligans(1);
        o.setMullgianType(MulliganType.LONDON); // sic: Tippfehler in XMage
        o.setMatchTimeLimit(MatchTimeLimit.NONE);
        o.setRollbackTurnsAllowed(false);
        o.setSpectatorsAllowed(false);
        o.setRated(false);
        return o;
    }

    @Override
    public void startGame() throws GameException {
        int startLife = options.isCustomStartLifeEnabled() ? options.getCustomStartLife() : 40;
        int startHand = options.isCustomStartHandSizeEnabled() ? options.getCustomStartHandSize() : 7;
        mulligan = new TrackingLondonMulligan(options.getFreeMulligans());
        CommanderFreeForAll game = new CommanderFreeForAll(options.getAttackOption(), options.getRange(), mulligan, startLife, startHand);
        game.setStartMessage(createGameStartMessage());
        initGame(game);
        games.add(game);
    }

    public TrackingLondonMulligan getMulligan() {
        return mulligan;
    }
}
