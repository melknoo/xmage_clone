package dev.magelite.stats;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profil- und Statistik-Endpunkte.
 */
public final class StatsRoutes implements HttpServer.Module {

    private final Db db;
    private final ProfileService profile;

    public StatsRoutes(Db db, ProfileService profile) {
        this.db = db;
        this.profile = profile;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/profile", ctx -> ctx.json(profile.view(Auth.user(ctx).id())));
        app.put("/api/profile", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            String name = b.path("name").asText("").strip();
            if (name.isEmpty() || name.length() > 24) {
                throw new IllegalArgumentException("Name: 1-24 Zeichen");
            }
            long userId = Auth.user(ctx).id();
            profile.rename(userId, name);
            ctx.json(profile.view(userId));
        });

        app.get("/api/stats/overview", ctx -> {
            long userId = Auth.user(ctx).id();
            ctx.json(db.with(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totals", one(c, """
                    SELECT COUNT(*) AS games,
                           SUM(CASE WHEN result='win' THEN 1 ELSE 0 END) AS wins,
                           AVG(placement) AS avgPlace,
                           AVG(turns) AS avgTurns,
                           AVG(duration_ms) AS avgDurationMs,
                           AVG(mulligans) AS avgMulligans,
                           SUM(CASE WHEN mulligans > 0 THEN 1 ELSE 0 END) AS gamesWithMulligan,
                           SUM(xp_awarded) AS xp
                    FROM games WHERE end_reason != 'error' AND user_id = ?""", userId));
            m.put("byTempo", rows(c, """
                    SELECT tempo, COUNT(*) AS games, SUM(CASE WHEN result='win' THEN 1 ELSE 0 END) AS wins, AVG(placement) AS avgPlace
                    FROM games WHERE end_reason != 'error' AND user_id = ? GROUP BY tempo ORDER BY games DESC""", userId));
            m.put("byMulligans", rows(c, """
                    SELECT mulligans, COUNT(*) AS games, SUM(CASE WHEN result='win' THEN 1 ELSE 0 END) AS wins
                    FROM games WHERE end_reason != 'error' AND user_id = ? GROUP BY mulligans ORDER BY mulligans""", userId));
            m.put("opponents", rows(c, """
                    SELECT s.commander AS commander, COUNT(*) AS games,
                           SUM(CASE WHEN g.result='win' THEN 1 ELSE 0 END) AS humanWins,
                           AVG(s.placement) AS avgPlace
                    FROM game_seats s JOIN games g ON g.id = s.game_id
                    WHERE s.is_human = 0 AND g.end_reason != 'error' AND g.user_id = ?
                    GROUP BY s.commander ORDER BY games DESC LIMIT 15""", userId));
            m.put("recentPlaces", rows(c, "SELECT placement, result, ended_at AS endedAt FROM games WHERE end_reason != 'error' AND user_id = ? ORDER BY ended_at DESC LIMIT 30", userId));
            return m;
            }));
        });

        app.get("/api/stats/decks", ctx -> ctx.json(db.with(c -> rows(c, """
                SELECT COALESCE(g.deck_id, -1) AS deckId, g.deck_name AS deckName, g.commander AS commander,
                       COUNT(*) AS games, SUM(CASE WHEN g.result='win' THEN 1 ELSE 0 END) AS wins,
                       AVG(g.placement) AS avgPlace, AVG(g.turns) AS avgTurns, AVG(g.mulligans) AS avgMulligans,
                       MAX(g.ended_at) AS lastPlayed, d.mastery_xp AS masteryXp, d.colors AS colors,
                       d.commander_set AS commanderSet, d.commander_num AS commanderNum
                FROM games g LEFT JOIN decks d ON d.id = g.deck_id
                WHERE g.end_reason != 'error' AND g.user_id = ?
                GROUP BY COALESCE(g.deck_id, g.deck_name) ORDER BY lastPlayed DESC""", Auth.user(ctx).id()))));

        app.get("/api/stats/decks/{id}/cards", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            long userId = Auth.user(ctx).id();
            ctx.json(db.with(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                int games;
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM games WHERE deck_id = ? AND end_reason != 'error' AND user_id = ?")) {
                    ps.setLong(1, id);
                    ps.setLong(2, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        games = rs.getInt(1);
                    }
                }
                m.put("games", games);
                m.put("cards", rows(c, """
                        SELECT s.card_name AS name,
                               SUM(s.opening) AS opening, SUM(s.drawn) AS drawn, SUM(CASE WHEN s.cast > 0 THEN 1 ELSE 0 END) AS gamesCast,
                               SUM(s.cast) AS cast, AVG(s.first_cast_turn) AS avgFirstCastTurn,
                               SUM(CASE WHEN s.cast > 0 AND g.result='win' THEN 1 ELSE 0 END) AS winsWhenCast,
                               COUNT(*) AS gamesSeen
                        FROM game_card_stats s JOIN games g ON g.id = s.game_id
                        WHERE s.deck_id = ? AND g.end_reason != 'error' AND g.user_id = ?
                        GROUP BY s.card_name ORDER BY gamesCast DESC, drawn DESC""", id, userId));
                m.put("commander", one(c, """
                        SELECT AVG(first_cast_turn) AS avgFirstCastTurn FROM game_card_stats s
                        JOIN games g ON g.id = s.game_id WHERE s.deck_id = ? AND s.card_name = g.commander AND g.user_id = ?""", id, userId));
                return m;
            }));
        });

        app.get("/api/history", ctx -> {
            int limit = Math.min(200, Integer.parseInt(ctx.queryParamAsClass("limit", String.class).getOrDefault("50")));
            long userId = Auth.user(ctx).id();
            ctx.json(db.with(c -> {
                List<Map<String, Object>> games = rows(c, """
                        SELECT id, started_at AS startedAt, ended_at AS endedAt, duration_ms AS durationMs, turns,
                               deck_id AS deckId, deck_name AS deckName, commander, result, placement, tempo, mulligans,
                               xp_awarded AS xp, end_reason AS endReason
                        FROM games WHERE user_id = ? ORDER BY ended_at DESC LIMIT ?""", userId, limit);
                for (Map<String, Object> g : games) {
                    g.put("seats", rows(c, "SELECT name, is_human AS human, deck_name AS deckName, commander, placement, eliminated_turn AS eliminatedTurn, life_end AS life FROM game_seats WHERE game_id = ? ORDER BY placement", g.get("id")));
                }
                return games;
            }));
        });
    }

    private static List<Map<String, Object>> rows(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    private static Map<String, Object> one(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> r = rows(c, sql, params);
        return r.isEmpty() ? Map.of() : r.get(0);
    }
}
