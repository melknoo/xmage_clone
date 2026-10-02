package dev.magelite.game;

import mage.players.net.SkipPrioritySteps;
import mage.players.net.UserData;
import mage.players.net.UserSkipPrioritySteps;

/**
 * UserData fuer den menschlichen Spieler. {@code HumanPlayer} liest Stops/Auto-Pass daraus
 * und wirft ohne UserData eine NPE.
 * <p>
 * Defaults (Goldfish-tauglich): Stop nur in den eigenen Mainphasen, bei Angriffen auf mich,
 * bei Blocks und bei neuen Objekten auf dem Stack; nach eigenem Cast automatisch passen.
 */
public final class HumanSettings {

    private HumanSettings() {
    }

    public static UserData defaults() {
        UserData data = UserData.getDefaultUserDataView();
        UserSkipPrioritySteps skips = new UserSkipPrioritySteps();
        SkipPrioritySteps mine = skips.getYourTurn();
        mine.setUpkeep(false);
        mine.setDraw(false);
        mine.setMain1(true);
        mine.setBeforeCombat(false);
        mine.setEndOfCombat(false);
        mine.setMain2(true);
        mine.setEndOfTurn(false);
        SkipPrioritySteps opp = skips.getOpponentTurn();
        opp.setUpkeep(false);
        opp.setDraw(false);
        opp.setMain1(false);
        opp.setBeforeCombat(false);
        opp.setEndOfCombat(false);
        opp.setMain2(false);
        opp.setEndOfTurn(false);
        skips.setStopOnDeclareAttackersDuringSkipActions(true);
        skips.setStopOnDeclareBlockersWithAnyPermanents(true);
        skips.setStopOnDeclareBlockersWithZeroPermanents(false);
        skips.setStopOnAllMainPhases(false);
        skips.setStopOnAllEndPhases(false);
        skips.setStopOnStackNewObjects(true);
        data.setUserSkipPrioritySteps(skips);
        data.setPassPriorityCast(true);
        data.setPassPriorityActivation(true);
        data.setConfirmEmptyManaPool(true);
        data.setManaPoolAutomatic(true);
        data.setManaPoolAutomaticRestricted(true);
        data.setAutoOrderTrigger(true);
        data.setUseFirstManaAbility(false);
        return data;
    }
}
