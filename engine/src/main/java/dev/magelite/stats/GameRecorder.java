package dev.magelite.stats;

import dev.magelite.game.GameHost;
import dev.magelite.game.GameSetup;
import dev.magelite.view.dto.Messages;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Speichert ein beendetes Spiel (einmalig) und vergibt XP an Held und Deck.
 */
public final class GameRecorder {

    private static final Logger LOG = Logger.getLogger(GameRecorder.class);

    public record XpPart(String source, String label, int amount) {
    }

    public record Reward(int xpGained, List<XpPart> breakdown, int level, int levelBefore, long xpTotal, int xpIntoLevel,
                         int xpForNext, String title, boolean levelUp, Long deckId, String deckName, Integer masteryGained,
                         Integer masteryLevel, Integer masteryLevelBefore, Integer masteryXp, Integer masteryNext) {
    }

    private final Db db;
    private final ProfileService profile;

    public GameRecorder(Db db, ProfileService profile) {
        this.db = db;
        this.profile = profile;
    }

    /**
     * @return Belohnung oder null (z.B. bei Fehlern)
     */
    public Reward record(GameHost host, Messages.GameOver over) {
        String gameId = host.getId().toString();
        StatsSink sink = StatsSink.of(host.getGame().getId());
        GameSetup setup = host.getSetup();
        try {
            return db.tx(c -> {
                if (exists(c, gameId)) {
                    return null;
                }
                Messages.Placement me = over.placements().stream().filter(Messages.Placement::human).findFirst().orElse(null);
                int place = me == null ? 4 : me.place();
                boolean won = me != null && over.winnerId() != null && over.winnerId().equals(me.playerId());
                int humanTurns = sink == null ? 0 : sink.humanTurns();
                boolean earlyConcede = host.isHumanConceded() && humanTurns < 3;
                Long deckId = setup.humanDeckId();
                long userId = setup.userId();
                String tempo = setup.tempo().name();
                ProfileService.ensure(c, userId, setup.humanName());
                long now = System.currentTimeMillis();

                // ---- XP
                List<XpPart> parts = new ArrayList<>();
                int xp = 0;
                if (!earlyConcede && over.error() == null) {
                    parts.add(new XpPart("base", "Teilnahme", 40));
                    int pl = Progression.placementXp(place);
                    if (pl > 0) {
                        parts.add(new XpPart("place", won ? "Sieg" : place + ". Platz", pl));
                    }
                    int survive = 3 * Math.min(humanTurns, 25);
                    if (survive > 0) {
                        parts.add(new XpPart("turns", humanTurns + " eigene Züge", survive));
                    }
                    if (won && !wonToday(c, userId)) {
                        parts.add(new XpPart("firstWin", "Erster Sieg des Tages", 100));
                    }
                    if (deckId != null && !playedDeckBefore(c, deckId)) {
                        parts.add(new XpPart("firstDeck", "Erstes Spiel mit dem Deck", 50));
                    }
                    int raw = parts.stream().mapToInt(XpPart::amount).sum();
                    double tf = Progression.tempoFactor(tempo);
                    int streak = won ? winStreak(c, userId) + 1 : 0;
                    double sf = Math.min(1.5, 1 + 0.1 * Math.max(0, streak - 1));
                    xp = (int) Math.round(raw * tf * sf);
                    if (tf != 1.0) {
                        parts.add(new XpPart("tempo", "Tempo " + tempo + " ×" + tf, (int) Math.round(raw * tf) - raw));
                    }
                    if (sf > 1.0) {
                        parts.add(new XpPart("streak", streak + "er-Siegesserie ×" + String.format(java.util.Locale.ROOT, "%.1f", sf),
                                xp - (int) Math.round(raw * tf)));
                    }
                }

                // ---- Spiel speichern
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO games (id, started_at, ended_at, duration_ms, turns, deck_id, deck_name, commander, result, placement, tempo, mulligans, xp_awarded, end_reason, user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, gameId);
                    ps.setLong(2, now - over.durationMs());
                    ps.setLong(3, now);
                    ps.setLong(4, over.durationMs());
                    ps.setInt(5, over.turns());
                    if (deckId == null) {
                        ps.setNull(6, java.sql.Types.INTEGER);
                    } else {
                        ps.setLong(6, deckId);
                    }
                    ps.setString(7, setup.humanDeck().name());
                    ps.setString(8, String.join(" & ", setup.humanDeck().commanders()));
                    ps.setString(9, won ? "win" : over.winnerId() == null ? "draw" : "loss");
                    ps.setInt(10, place);
                    ps.setString(11, tempo);
                    ps.setInt(12, me == null ? 0 : me.mulligans());
                    ps.setInt(13, xp);
                    ps.setString(14, over.error() != null ? "error" : host.isHumanConceded() ? "concede" : "normal");
                    ps.setLong(15, userId);
                    ps.executeUpdate();
                }
                int seat = 0;
                Map<UUID, String> decks = host.getDeckNames();
                Map<UUID, List<String>> cmds = host.getCommanders();
                for (Messages.Placement p : over.placements()) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO game_seats (game_id, seat, name, is_human, deck_name, commander, placement, eliminated_turn, life_end, mulligans) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                        ps.setString(1, gameId);
                        ps.setInt(2, seat++);
                        ps.setString(3, p.name());
                        ps.setInt(4, p.human() ? 1 : 0);
                        ps.setString(5, decks.get(p.playerId()));
                        ps.setString(6, String.join(" & ", cmds.getOrDefault(p.playerId(), List.of())));
                        ps.setInt(7, p.place());
                        if (p.eliminatedTurn() == null) {
                            ps.setNull(8, java.sql.Types.INTEGER);
                        } else {
                            ps.setInt(8, p.eliminatedTurn());
                        }
                        ps.setInt(9, p.life());
                        ps.setInt(10, p.mulligans());
                        ps.executeUpdate();
                    }
                }
                if (sink != null) {
                    for (Map.Entry<String, StatsSink.CardStat> e : sink.cards().entrySet()) {
                        try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO game_card_stats (game_id, deck_id, card_name, opening, drawn, cast, first_cast_turn) VALUES (?,?,?,?,?,?,?)")) {
                            StatsSink.CardStat s = e.getValue();
                            ps.setString(1, gameId);
                            if (deckId == null) {
                                ps.setNull(2, java.sql.Types.INTEGER);
                            } else {
                                ps.setLong(2, deckId);
                            }
                            ps.setString(3, e.getKey());
                            ps.setInt(4, s.opening ? 1 : 0);
                            ps.setInt(5, s.drawn);
                            ps.setInt(6, s.cast + s.played);
                            if (s.firstCastTurn == null) {
                                ps.setNull(7, java.sql.Types.INTEGER);
                            } else {
                                ps.setInt(7, s.firstCastTurn);
                            }
                            ps.executeUpdate();
                        }
                    }
                }

                // ---- XP verbuchen
                long before = profile.xpTotal(c, userId);
                Progression.Level lvBefore = Progression.levelOf(before);
                for (XpPart p : parts) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO xp_ledger (game_id, deck_id, source, amount, ts, user_id) VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, gameId);
                        if (deckId == null) {
                            ps.setNull(2, java.sql.Types.INTEGER);
                        } else {
                            ps.setLong(2, deckId);
                        }
                        ps.setString(3, p.source());
                        ps.setInt(4, p.amount());
                        ps.setLong(5, now);
                        ps.setLong(6, userId);
                        ps.executeUpdate();
                    }
                }
                long after = before + xp;
                profile.setXpTotal(c, userId, after);
                Progression.Level lv = Progression.levelOf(after);

                Integer mGained = null, mLevel = null, mBefore = null, mXp = null, mNext = null;
                if (deckId != null) {
                    int mxBefore = deckXp(c, deckId);
                    try (PreparedStatement ps = c.prepareStatement("UPDATE decks SET mastery_xp = mastery_xp + ? WHERE id = ?")) {
                        ps.setInt(1, xp);
                        ps.setLong(2, deckId);
                        ps.executeUpdate();
                    }
                    mGained = xp;
                    mXp = mxBefore + xp;
                    mBefore = Progression.masteryLevel(mxBefore);
                    mLevel = Progression.masteryLevel(mXp);
                    mNext = Progression.masteryNext(mXp);
                }
                return new Reward(xp, parts, lv.level(), lvBefore.level(), after, lv.xpIntoLevel(), lv.xpForNext(),
                        Progression.titleOf(lv.level()), lv.level() > lvBefore.level(), deckId, setup.humanDeck().name(),
                        mGained, mLevel, mBefore, mXp, mNext);
            });
        } catch (RuntimeException e) {
            LOG.error("Spiel konnte nicht gespeichert werden", e);
            return null;
        } finally {
            StatsSink.unregister(host.getGame().getId());
        }
    }

    private static boolean exists(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM games WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean wonToday(Connection c, long userId) throws SQLException {
        long start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM games WHERE result = 'win' AND ended_at >= ? AND user_id = ? LIMIT 1")) {
            ps.setLong(1, start);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean playedDeckBefore(Connection c, long deckId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM games WHERE deck_id = ? LIMIT 1")) {
            ps.setLong(1, deckId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Anzahl Siege in Folge vor diesem Spiel. */
    static int winStreak(Connection c, long userId) throws SQLException {
        int n = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT result FROM games WHERE end_reason != 'error' AND user_id = ? ORDER BY ended_at DESC LIMIT 50")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && "win".equals(rs.getString(1))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int deckXp(Connection c, long deckId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT mastery_xp FROM decks WHERE id = ?")) {
            ps.setLong(1, deckId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
