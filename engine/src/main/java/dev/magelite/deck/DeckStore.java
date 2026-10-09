package dev.magelite.deck;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import dev.magelite.api.Json;
import dev.magelite.stats.Db;
import dev.magelite.stats.Progression;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eigene Decks eines Nutzers (SQLite); lokal ist das immer Nutzer 1. Gespeichert wird MageLite-Text v2
 * ({@code deck_format=2}); Altbestand im XMage-.dck-Format stellt {@link DeckMigration} beim Start um.
 */
public final class DeckStore {

    /**
     * {@code masteryLevel}/{@code masteryNext} aus {@link Progression}; {@code games}/{@code wins} (Spiele ohne Fehlerende)
     * nur in {@link #list}, sonst null und weggelassen. {@code folder} '' = ohne Ordner; {@code bracket} manuell (null =
     * Vorschlag {@code bracketAuto} gilt), {@code bracketInfo} = Gruende des Vorschlags (JSON-Liste). {@code sortOrder}:
     * eigene Reihenfolge im Ordner (null = neu/nie sortiert; die UI zeigt diese zuerst, nach {@code updatedAt}).
     */
    public record StoredDeck(long id, String name, List<String> commanders, String colors, String commanderSet,
                             String commanderNum, String source, String sourceUrl, int cardCount, boolean valid,
                             String validation, int masteryXp, long createdAt, long updatedAt,
                             int masteryLevel, int masteryNext,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer games,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer wins,
                             String folder,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer bracket,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer bracketAuto,
                             @JsonInclude(JsonInclude.Include.NON_NULL) @JsonRawValue String bracketInfo,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer sortOrder) {

        StoredDeck withStats(int games, int wins) {
            return new StoredDeck(id, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, cardCount, valid,
                    validation, masteryXp, createdAt, updatedAt, masteryLevel, masteryNext, games, wins, folder, bracket,
                    bracketAuto, bracketInfo, sortOrder);
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
                     String source, String sourceUrl, String dck, int cardCount, boolean valid, String validation,
                     BracketAnalyzer.Result bracketAuto) {
        long now = System.currentTimeMillis();
        String info = infoJson(bracketAuto);
        return db.with(c -> {
            if (id == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO decks (name, commanders, colors, commander_set, commander_num, source, source_url, dck, card_count, valid, validation, bracket_auto, bracket_info, created_at, updated_at, user_id, deck_format) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,2)", Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, dck, cardCount, valid, validation, bracketAuto, info);
                    ps.setLong(14, now);
                    ps.setLong(15, now);
                    ps.setLong(16, userId);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        return keys.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE decks SET name=?, commanders=?, colors=?, commander_set=?, commander_num=?, source=?, source_url=?, dck=?, card_count=?, valid=?, validation=?, bracket_auto=?, bracket_info=?, updated_at=?, deck_format=2 WHERE id=? AND user_id=?")) {
                bind(ps, name, commanders, colors, commanderSet, commanderNum, source, sourceUrl, dck, cardCount, valid, validation, bracketAuto, info);
                ps.setLong(14, now);
                ps.setLong(15, id);
                ps.setLong(16, userId);
                if (ps.executeUpdate() == 0) {
                    throw new IllegalArgumentException("Deck nicht gefunden");
                }
                return id;
            }
        });
    }

    /**
     * Ordner/Bracket aendern, ohne das Deck neu zu speichern ({@code updated_at} bleibt). {@code folder} null = unveraendert;
     * {@code bracket} null = unveraendert, 0 = Vorschlag gilt, 1-5 = manuell.
     */
    public boolean patchMeta(long userId, long id, String folder, Integer bracket) {
        if (bracket != null && (bracket < 0 || bracket > 5)) {
            throw new IllegalArgumentException("Bracket muss 1-5 sein");
        }
        String f = folder == null ? null : normalizeFolder(folder);
        return db.with(c -> {
            int n = 0;
            if (f != null) {
                // anderer Ordner -> eigene Reihenfolge verwerfen (das Deck erscheint dort vorne)
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE decks SET sort_order = CASE WHEN folder = ? THEN sort_order END, folder = ? WHERE id = ? AND user_id = ?")) {
                    ps.setString(1, f);
                    ps.setString(2, f);
                    ps.setLong(3, id);
                    ps.setLong(4, userId);
                    n += ps.executeUpdate();
                }
            }
            if (bracket != null) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE decks SET bracket = ? WHERE id = ? AND user_id = ?")) {
                    if (bracket == 0) {
                        ps.setNull(1, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(1, bracket);
                    }
                    ps.setLong(2, id);
                    ps.setLong(3, userId);
                    n += ps.executeUpdate();
                }
            }
            return n > 0;
        });
    }

    /**
     * Reihenfolge eines Ordners festlegen: {@code ids} landen (in dieser Reihenfolge) im Ordner {@code folder}, z.B. nach
     * Drag & Drop. Fremde/unbekannte IDs werden ignoriert (user_id-Filter). Liefert die Anzahl geaenderter Decks.
     */
    public int reorder(long userId, String folder, List<Long> ids) {
        String f = normalizeFolder(folder);
        return db.tx(c -> {
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement("UPDATE decks SET folder = ?, sort_order = ? WHERE id = ? AND user_id = ?")) {
                for (int i = 0; i < ids.size(); i++) {
                    ps.setString(1, f);
                    ps.setInt(2, i);
                    ps.setLong(3, ids.get(i));
                    ps.setLong(4, userId);
                    n += ps.executeUpdate();
                }
            }
            return n;
        });
    }

    /** Ordner umbenennen bzw. mit {@code to} = '' aufloesen (Decks landen ohne Ordner). Liefert die Anzahl Decks. */
    public int renameFolder(long userId, String from, String to) {
        String f = normalizeFolder(from);
        String t = normalizeFolder(to);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE decks SET folder = ? WHERE folder = ? AND user_id = ?")) {
                ps.setString(1, t);
                ps.setString(2, f);
                ps.setLong(3, userId);
                return ps.executeUpdate();
            }
        });
    }

    /** Deck im alten XMage-Format (vor Forge), {@code commanders} = gespeicherte Namen. */
    public record LegacyDeck(long id, String name, List<String> commanders, String dck) {
    }

    /** Alle Decks mit {@code deck_format=1}, alle Nutzer (nur fuer {@link DeckMigration}). */
    List<LegacyDeck> legacyDecks() {
        return db.with(c -> {
            List<LegacyDeck> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, commanders, dck FROM decks WHERE deck_format = 1 ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cmds = rs.getString(3);
                    out.add(new LegacyDeck(rs.getLong(1), rs.getString(2),
                            cmds == null || cmds.isBlank() ? List.of() : Arrays.asList(cmds.split("\n")), rs.getString(4)));
                }
            }
            return out;
        });
    }

    /** Umgestelltes Deck schreiben; alter Text nach {@code dck_legacy}. {@code updated_at}, Ordner, Sortierung, Meisterschaft bleiben. */
    void migrate(long id, DeckMigration.Converted cv) {
        String info = infoJson(cv.bracket());
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE decks SET dck_legacy=dck, dck=?, commanders=?, colors=?, commander_set=?, commander_num=?, card_count=?, valid=?, validation=?, "
                            + "bracket_auto=?, bracket_info=?, deck_format=2 WHERE id=? AND deck_format=1")) {
                ps.setString(1, cv.text());
                ps.setString(2, String.join("\n", cv.commanders()));
                ps.setString(3, cv.colors() == null ? "" : cv.colors());
                ps.setString(4, cv.commanderSet());
                ps.setString(5, cv.commanderNum());
                ps.setInt(6, cv.cardCount());
                ps.setInt(7, cv.valid() ? 1 : 0);
                ps.setString(8, cv.validation());
                if (cv.bracket() == null) {
                    ps.setNull(9, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(9, cv.bracket().bracket());
                }
                ps.setString(10, info);
                ps.setLong(11, id);
                return ps.executeUpdate();
            }
        });
    }

    /** Decks ohne Bracket-Vorschlag (Altbestand vor V6), alle Nutzer: id -> .dck */
    public Map<Long, String> withoutBracketAuto() {
        return db.with(c -> {
            Map<Long, String> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, dck FROM decks WHERE bracket_auto IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getString(2));
                }
            }
            return out;
        });
    }

    public void setBracketAuto(long id, BracketAnalyzer.Result r) {
        String info = infoJson(r);
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE decks SET bracket_auto = ?, bracket_info = ? WHERE id = ?")) {
                ps.setInt(1, r.bracket());
                ps.setString(2, info);
                ps.setLong(3, id);
                return ps.executeUpdate();
            }
        });
    }

    /** Ordnername bereinigen: getrimmt, Leerraum zusammengefasst, max. 40 Zeichen ('' = ohne Ordner). */
    static String normalizeFolder(String folder) {
        String f = folder == null ? "" : folder.strip().replaceAll("\\s+", " ");
        if (f.length() > 40) {
            throw new IllegalArgumentException("Ordnername zu lang (max. 40 Zeichen)");
        }
        return f;
    }

    private static String infoJson(BracketAnalyzer.Result r) {
        if (r == null) {
            return null;
        }
        try {
            return Json.MAPPER.writeValueAsString(r.reasons());
        } catch (Exception e) {
            return null;
        }
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
                             String validation, BracketAnalyzer.Result bracketAuto, String bracketInfo) throws java.sql.SQLException {
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
        if (bracketAuto == null) {
            ps.setNull(12, java.sql.Types.INTEGER);
        } else {
            ps.setInt(12, bracketAuto.bracket());
        }
        ps.setString(13, bracketInfo);
    }

    private static StoredDeck read(ResultSet rs) throws java.sql.SQLException {
        String cmds = rs.getString("commanders");
        return new StoredDeck(rs.getLong("id"), rs.getString("name"),
                cmds == null || cmds.isEmpty() ? List.of() : Arrays.asList(cmds.split("\n")),
                rs.getString("colors"), rs.getString("commander_set"), rs.getString("commander_num"),
                rs.getString("source"), rs.getString("source_url"), rs.getInt("card_count"), rs.getInt("valid") != 0,
                rs.getString("validation"), rs.getInt("mastery_xp"), rs.getLong("created_at"), rs.getLong("updated_at"),
                Progression.masteryLevel(rs.getInt("mastery_xp")), Progression.masteryNext(rs.getInt("mastery_xp")), null, null,
                rs.getString("folder"), intOrNull(rs, "bracket"), intOrNull(rs, "bracket_auto"), rs.getString("bracket_info"),
                intOrNull(rs, "sort_order"));
    }

    private static Integer intOrNull(ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
