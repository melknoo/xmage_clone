package mage.player.ai.score;

import dev.magelite.game.BotTuning;
import mage.abilities.Ability;
import mage.abilities.effects.Effect;
import mage.constants.Outcome;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MageLite-Ersatz fuer die gleichnamige Klasse aus {@code mage-player-ai-1.4.60.jar} (XMage, MIT-Lizenz,
 * Original: Tag {@code xmage_1.4.60V3}, Mage.Server.Plugins/Mage.Player.AI/.../score/GameStateEvaluator2.java,
 * Autor nantuko).
 * <p>
 * Wird geladen, weil das Engine-Jar auf dem Classpath VOR den XMage-Jars steht (Jars bleiben unveraendert).
 * Oeffentliche API identisch zum Original - {@code ComputerPlayer6/7} rufen {@code evaluate} statisch auf.
 * <p>
 * Das Original bewertet nur gegen den ERSTEN Gegner ("only good for two player games"): Schaden/Removal gegen die
 * anderen zaehlt nicht, und faellt der erste Gegner auf 0 Leben, gilt das als Partiesieg. Die FFA-Bewertung zieht
 * {@code w * staerkster Gegner + (1 - w) * Summe aller Gegner / Gegnerzahl} ab und gibt pro ausgeschiedenem Gegner
 * einen Bonus. Pro Spieler abschaltbar ueber {@link BotTuning} (dann Original-Verhalten).
 */
public final class GameStateEvaluator2 {

    private static final Logger logger = Logger.getLogger(GameStateEvaluator2.class);

    /** Marker fuer die Selbstpruefung beim Start ({@link BotTuning#checkFfaEvaluator()}) */
    public static final boolean MAGELITE_FFA = true;

    public static final int WIN_GAME_SCORE = 100000000;
    public static final int LOSE_GAME_SCORE = -WIN_GAME_SCORE;

    public static final int HAND_CARD_SCORE = 5;

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game) {
        return evaluate(playerId, game, true);
    }

    public static PlayerEvaluateScore evaluate(UUID playerId, Game game, boolean useCombatPermanentScore) {
        if (BotTuning.enabled(playerId, BotTuning.Lever.FFA_EVAL)) {
            return evaluateFfa(playerId, game, useCombatPermanentScore);
        }
        return evaluateFirstOpponent(playerId, game, useCombatPermanentScore);
    }

    private static PlayerEvaluateScore evaluateFfa(UUID playerId, Game game, boolean useCombatPermanentScore) {
        Player player = game.getPlayer(playerId);
        // alle Mitspieler inkl. ausgeschiedener (getOpponents laesst diese nach dem naechsten Range-Update weg,
        // dann wuerden Teiler und Bonus springen); FFA: jeder andere ist Gegner
        List<Player> opponents = new ArrayList<>();
        for (Player p : game.getPlayers().values()) {
            if (p != null && !p.getId().equals(playerId)) {
                opponents.add(p);
            }
        }
        if (player == null || opponents.isEmpty()) {
            return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
        }

        if (player.hasLost() || player.getLife() <= 0) { // we don't want a tie
            return new PlayerEvaluateScore(playerId, LOSE_GAME_SCORE);
        }
        if (player.hasWon()) {
            return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
        }

        List<Player> alive = new ArrayList<>();
        for (Player opponent : opponents) {
            if (opponent.hasWon()) {
                return new PlayerEvaluateScore(playerId, LOSE_GAME_SCORE);
            }
            if (!opponent.hasLost() && opponent.isInGame() && opponent.getLife() > 0) {
                alive.add(opponent);
            }
        }
        if (alive.isEmpty()) {
            return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
        }

        // ein Durchlauf ueber das Spielfeld fuer alle Spieler (Bewertung laeuft pro Suchknoten)
        Map<UUID, Integer> permanents = permanentsScoreByController(game, useCombatPermanentScore);
        int playerLifeScore = ArtificialScoringSystem.getLifeScore(player.getLife());
        int playerHandScore = player.getHand().size() * HAND_CARD_SCORE;
        int playerPermanentsScore = permanents.getOrDefault(playerId, 0);

        // Gegner einzeln bewerten; Aggregat komponentenweise, damit getOpponentScore() = Aggregat bleibt
        double w = BotTuning.ffaMaxWeight;
        int n = opponents.size();
        int[][] parts = new int[alive.size()][3];
        int best = 0;
        for (int i = 0; i < alive.size(); i++) {
            Player opponent = alive.get(i);
            parts[i][0] = ArtificialScoringSystem.getLifeScore(opponent.getLife());
            parts[i][1] = opponent.getHand().size() * HAND_CARD_SCORE;
            parts[i][2] = permanents.getOrDefault(opponent.getId(), 0);
            if (sum(parts[i]) > sum(parts[best])) {
                best = i;
            }
        }
        int[] agg = new int[3];
        for (int c = 0; c < 3; c++) {
            double total = 0;
            for (int[] part : parts) {
                total += part[c];
            }
            agg[c] = (int) Math.round(w * parts[best][c] + (1 - w) * total / n);
        }
        int eliminationScore = (n - alive.size()) * BotTuning.ffaEliminationBonus;

        PlayerEvaluateScore score = new PlayerEvaluateScore(
                playerId,
                playerLifeScore, playerHandScore, playerPermanentsScore,
                agg[0], agg[1], agg[2]);
        score.eliminationScore = eliminationScore;
        if (logger.isDebugEnabled()) {
            logger.debug(score.getTotalScore() + " total FFA score (player " + score.getPlayerInfoShort()
                    + " opponents " + score.getOpponentInfoShort() + " eliminated " + (n - alive.size()) + ')');
        }
        return score;
    }

    private static int sum(int[] parts) {
        return parts[0] + parts[1] + parts[2];
    }

    private static Map<UUID, Integer> permanentsScoreByController(Game game, boolean useCombatPermanentScore) {
        Map<UUID, Integer> scores = new HashMap<>();
        try {
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents()) {
                scores.merge(permanent.getControllerId(), evaluatePermanent(permanent, game, useCombatPermanentScore), Integer::sum);
            }
        } catch (Throwable t) {
            // wie im Original: Bewertung darf die Suche nie abbrechen
        }
        return scores;
    }

    private static int permanentsScore(UUID playerId, Game game, boolean useCombatPermanentScore) {
        int score = 0;
        try {
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
                score += evaluatePermanent(permanent, game, useCombatPermanentScore);
            }
        } catch (Throwable t) {
            // wie im Original: Bewertung darf die Suche nie abbrechen
        }
        return score;
    }

    /** Original-Logik aus XMage 1.4.60 (nur erster Gegner), unveraendert bis auf die entfernte Debug-Ausgabe. */
    private static PlayerEvaluateScore evaluateFirstOpponent(UUID playerId, Game game, boolean useCombatPermanentScore) {
        Player player = game.getPlayer(playerId);
        // must find all leaved opponents
        Player opponent = game.getPlayer(game.getOpponents(playerId, false).stream().findFirst().orElse(null));
        if (opponent == null) {
            return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
        }

        if (game.checkIfGameIsOver()) {
            if (player.hasLost()
                    || opponent.hasWon()) {
                return new PlayerEvaluateScore(playerId, LOSE_GAME_SCORE);
            }
            if (opponent.hasLost()
                    || player.hasWon()) {
                return new PlayerEvaluateScore(playerId, WIN_GAME_SCORE);
            }
        }

        int playerLifeScore = 0;
        int opponentLifeScore = 0;
        if (player.getLife() <= 0) { // we don't want a tie
            playerLifeScore = ArtificialScoringSystem.LOSE_GAME_SCORE;
        } else if (opponent.getLife() <= 0) {
            playerLifeScore = ArtificialScoringSystem.WIN_GAME_SCORE;
        } else {
            playerLifeScore = ArtificialScoringSystem.getLifeScore(player.getLife());
            opponentLifeScore = ArtificialScoringSystem.getLifeScore(opponent.getLife());
        }

        int playerPermanentsScore = permanentsScore(playerId, game, useCombatPermanentScore);
        int opponentPermanentsScore = permanentsScore(opponent.getId(), game, useCombatPermanentScore);

        int playerHandScore = player.getHand().size() * HAND_CARD_SCORE;
        int opponentHandScore = opponent.getHand().size() * HAND_CARD_SCORE;

        return new PlayerEvaluateScore(
                playerId,
                playerLifeScore, playerHandScore, playerPermanentsScore,
                opponentLifeScore, opponentHandScore, opponentPermanentsScore);
    }

    public static int evaluatePermanent(Permanent permanent, Game game, boolean useCombatPermanentScore) {
        // prevent AI from attaching bad auras to its own permanents ex: Brainwash and Demonic Torment (no immediate penalty on the battlefield)
        int value = 0;
        if (!permanent.getAttachments().isEmpty()) {
            for (UUID attachmentId : permanent.getAttachments()) {
                Permanent attachment = game.getPermanent(attachmentId);
                for (Ability a : attachment.getAbilities(game)) {
                    for (Effect e : a.getEffects()) {
                        if (e.getOutcome().equals(Outcome.Detriment)
                                && attachment.getControllerId().equals(permanent.getControllerId())) {
                            value -= 1000;  // seems to work well ; -300 is not effective enough
                        }
                    }
                }
            }
        }
        value += ArtificialScoringSystem.getFixedPermanentScore(game, permanent);
        value += ArtificialScoringSystem.getDynamicPermanentScore(game, permanent);
        if (useCombatPermanentScore) {
            value += ArtificialScoringSystem.getCombatPermanentScore(game, permanent);
        }
        return value;
    }

    public static class PlayerEvaluateScore {

        private UUID playerId;
        private int playerLifeScore = 0;
        private int playerHandScore = 0;
        private int playerPermanentsScore = 0;

        private int opponentLifeScore = 0;
        private int opponentHandScore = 0;
        private int opponentPermanentsScore = 0;

        private int specialScore = 0; // special score (ignore all others, e.g. for win/lose game states)

        /** MageLite: Bonus fuer ausgeschiedene Gegner (FFA), zaehlt zum Spieler-Score */
        private int eliminationScore = 0;

        public PlayerEvaluateScore(UUID playerId, int specialScore) {
            this.playerId = playerId;
            this.specialScore = specialScore;
        }

        public PlayerEvaluateScore(UUID playerId,
                                   int playerLifeScore, int playerHandScore, int playerPermanentsScore,
                                   int opponentLifeScore, int opponentHandScore, int opponentPermanentsScore) {
            this.playerId = playerId;
            this.playerLifeScore = playerLifeScore;
            this.playerHandScore = playerHandScore;
            this.playerPermanentsScore = playerPermanentsScore;
            this.opponentLifeScore = opponentLifeScore;
            this.opponentHandScore = opponentHandScore;
            this.opponentPermanentsScore = opponentPermanentsScore;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public int getPlayerScore() {
            return playerLifeScore + playerHandScore + playerPermanentsScore + eliminationScore;
        }

        public int getOpponentScore() {
            return opponentLifeScore + opponentHandScore + opponentPermanentsScore;
        }

        public int getTotalScore() {
            if (specialScore != 0) {
                return specialScore;
            } else {
                return getPlayerScore() - getOpponentScore();
            }
        }

        public int getPlayerLifeScore() {
            return playerLifeScore;
        }

        public int getPlayerHandScore() {
            return playerHandScore;
        }

        public int getPlayerPermanentsScore() {
            return playerPermanentsScore;
        }

        public String getPlayerInfoFull() {
            return "Life:" + playerLifeScore
                    + ", Hand:" + playerHandScore
                    + ", Perm:" + playerPermanentsScore
                    + (eliminationScore != 0 ? ", Elim:" + eliminationScore : "");
        }

        public String getPlayerInfoShort() {
            return "L:" + playerLifeScore
                    + ",H:" + playerHandScore
                    + ",P:" + playerPermanentsScore
                    + (eliminationScore != 0 ? ",E:" + eliminationScore : "");
        }

        public String getOpponentInfoFull() {
            return "Life:" + opponentLifeScore
                    + ", Hand:" + opponentHandScore
                    + ", Perm:" + opponentPermanentsScore;
        }

        public String getOpponentInfoShort() {
            return "L:" + opponentLifeScore
                    + ",H:" + opponentHandScore
                    + ",P:" + opponentPermanentsScore;
        }
    }
}
