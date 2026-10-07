package dev.magelite.deck;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.magelite.stats.Db;
import dev.magelite.stats.Progression;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eigene Decks eines Nutzers (SQLite); lokal ist das immer Nutzer 1. Gespeichert wird der .dck-Text (XMage-Format).
 */
public final class DeckStore {

    /**
     * {@code masteryLevel}/{@code masteryNext} aus {@link Progression}; {@code games}/{@code wins} (Spiele ohne Fehlerende)
     * nur in {@link #list}, sonst null und weggelassen.
     */
    public record StoredDeck(long id, String name, List<String> commanders, String colors, String commanderSet,
                             String commanderNum, String source, String sourceUrl, int cardCount, boolean valid,
                             String validation, int masteryXp, long createdAt, long updatedAt,
                             int masteryLevel, int masteryNext,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer games,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer wins) {

        StoredDeck withStats(int games, int wins) {
            return new StoredDeck(id, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, cardCount, valid,
                    validation, masteryXp, createdAt, updatedAt, masteryLevel, masteryNext, games, wins);
        }
    }

    private final Db db;

    public DeckStore(Db db) {
        this.db = db;
    }

    public List<StoredDeck> list(long userId) {
        return db.with(c -> {
            List<StoredDeck> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM decks WHERE user_id = ? ORDER BY updated_at DESC")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs));
                    }
                }
            }
            // Spiele/Siege je Deck: eine Abfrage, in Java zusammengefuehrt (read() bleibt fuer get() gleich)
            Map<Long, int[]> stats = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT deck_id, COUNT(*), SUM(CASE WHEN result='win' THEN 1 ELSE 0 END) FROM games "
                            + "WHERE user_id = ? AND end_reason != 'error' AND deck_id IS NOT NULL GROUP BY deck_id")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stats.put(rs.getLong(1), new int[]{rs.getInt(2), rs.getInt(3)});
                    }
                }
            }
            out.replaceAll(d -> {
                int[] gw = stats.getOrDefault(d.id(), new int[]{0, 0});
                return d.withStats(gw[0], gw[1]);
            });
            return out;
        });
    }

    public Optional<StoredDeck> get(long userId, long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM decks WHERE id = ? AND user_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<StoredDeck>empty();
                }
            }
        });
    }

    public Optional<String> getDck(long userId, long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT dck FROM decks WHERE id = ? AND user_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    public long save(long userId, Long id, String name, List<String> commanders, String colors, String commanderSet, String commanderNum,
                     String source, String sourceUrl, String dck, int cardCount, boolean valid, String validation) {
        long now = System.currentTimeMillis();
        return db.with(c -> {
            if (id == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO decks (name, commanders, colors, commander_set, commander_num, source, source_url, dck, card_count, valid, validation, created_at, updated_at, user_id) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, dck, cardCount, valid, validation);
                    ps.setLong(12, now);
                    ps.setLong(13, now);
                    ps.setLong(14, userId);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return keys.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE decks SET name=?, commanders=?, colors=?, commander_set=?, commander_num=?, source=?, source_url=?, dck=?, card_count=?, valid=?, validation=?, updated_at=? WHERE id=? AND user_id=?")) {
                bind(ps, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, dck, cardCount, valid, validation);
                ps.setLong(12, now);
                ps.setLong(13, id);
                ps.setLong(14, userId);
                if (ps.executeUpdate() == 0) {
                    throw new IllegalArgumentException("Deck nicht gefunden");
                }
                return id;
            }
        });
    }

    public boolean delete(long userId, long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM decks WHERE id = ? AND user_id = ?")) {
                ps.setLong(1, id);
                ps.setLong(2, userId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public void addMasteryXp(long id, int xp) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE decks SET mastery_xp = mastery_xp + ? WHERE id = ?")) {
                ps.setInt(1, xp);
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        });
    }

    private static void bind(PreparedStatement ps, String name, List<String> commanders, String colors, String commanderSet,
                             String commanderNum, String source, String sourceUrl, String dck, int cardCount, boolean valid,
                             String validation) throws java.sql.SQLException {
        ps.setString(1, name);
        ps.setString(2, String.join("\n", commanders));
        ps.setString(3, colors == null ? "" : colors);
        ps.setString(4, commanderSet);
        ps.setString(5, commanderNum);
        ps.setString(6, source == null ? "text" : source);
        ps.setString(7, sourceUrl);
        ps.setString(8, dck);
        ps.setInt(9, cardCount);
        ps.setInt(10, valid ? 1 : 0);
        ps.setString(11, validation);
    }

    private static StoredDeck read(ResultSet rs) throws java.sql.SQLException {
        String cmds = rs.getString("commanders");
        return new StoredDeck(rs.getLong("id"), rs.getString("name"),
                cmds == null || cmds.isEmpty() ? List.of() : Arrays.asList(cmds.split("\n")),
                rs.getString("colors"), rs.getString("commander_set"), rs.getString("commander_num"),
                rs.getString("source"), rs.getString("source_url"), rs.getInt("card_count"), rs.getInt("valid") != 0,
                rs.getString("validation"), rs.getInt("mastery_xp"), rs.getLong("created_at"), rs.getLong("updated_at"),
                Progression.masteryLevel(rs.getInt("mastery_xp")), Progression.masteryNext(rs.getInt("mastery_xp")), null, null);
    }
}
