package dev.magelite.deck;

import dev.magelite.stats.Db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Eigene Decks des Spielers (SQLite). Gespeichert wird der .dck-Text (XMage-Format).
 */
public final class DeckStore {

    public record StoredDeck(long id, String name, List<String> commanders, String colors, String commanderSet,
                             String commanderNum, String source, String sourceUrl, int cardCount, boolean valid,
                             String validation, int masteryXp, long createdAt, long updatedAt) {
    }

    private final Db db;

    public DeckStore(Db db) {
        this.db = db;
    }

    public List<StoredDeck> list() {
        return db.with(c -> {
            List<StoredDeck> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM decks ORDER BY updated_at DESC")) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        });
    }

    public Optional<StoredDeck> get(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM decks WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<StoredDeck>empty();
                }
            }
        });
    }

    public Optional<String> getDck(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT dck FROM decks WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    public long save(Long id, String name, List<String> commanders, String colors, String commanderSet, String commanderNum,
                     String source, String sourceUrl, String dck, int cardCount, boolean valid, String validation) {
        long now = System.currentTimeMillis();
        return db.with(c -> {
            if (id == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO decks (name, commanders, colors, commander_set, commander_num, source, source_url, dck, card_count, valid, validation, created_at, updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, dck, cardCount, valid, validation);
                    ps.setLong(12, now);
                    ps.setLong(13, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return keys.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE decks SET name=?, commanders=?, colors=?, commander_set=?, commander_num=?, source=?, source_url=?, dck=?, card_count=?, valid=?, validation=?, updated_at=? WHERE id=?")) {
                bind(ps, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, dck, cardCount, valid, validation);
                ps.setLong(12, now);
                ps.setLong(13, id);
                ps.executeUpdate();
                return id;
            }
        });
    }

    public boolean delete(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM decks WHERE id = ?")) {
                ps.setLong(1, id);
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
                rs.getString("validation"), rs.getInt("mastery_xp"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
