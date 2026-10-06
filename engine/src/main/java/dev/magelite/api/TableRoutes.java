package dev.magelite.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.auth.User;
import dev.magelite.deck.DeckResolver;
import dev.magelite.game.TableManager;
import dev.magelite.game.TempoSettings;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * REST fuer Lobby und Tische (Server-Modus). Die Lobby synchronisiert sich per Polling auf
 * {@code GET /api/tables/{id}} (ca. alle 1,5 s).
 */
public final class TableRoutes implements HttpServer.Module {

    private final TableManager tables;
    private final DeckResolver decks;
    private volatile BiConsumer<Long, String> onJoined = (userId, tableId) -> {
    };

    public TableRoutes(TableManager tables, DeckResolver decks) {
        this.tables = tables;
        this.decks = decks;
    }

    /** Wird nach einem Beitritt aufgerufen (Nutzer, Tisch), z.B. um Einladungen zu erledigen. */
    public void setOnJoined(BiConsumer<Long, String> cb) {
        this.onJoined = cb;
    }

    @Override
    public void register(Javalin app) {
        app.exception(TableManager.TableException.class, (e, ctx) -> ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage())));

        app.get("/api/tables", ctx -> {
            User u = Auth.user(ctx);
            List<Map<String, Object>> out = new ArrayList<>();
            for (TableManager.Table t : tables.list()) {
                out.add(view(t, u));
            }
            ctx.json(out);
        });
        app.get("/api/tables/mine", ctx -> {
            User u = Auth.user(ctx);
            var t = tables.mine(u.id());
            if (t.isPresent()) {
                ctx.json(view(t.get(), u));
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "kein Tisch"));
            }
        });
        app.post("/api/tables", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            ctx.json(view(tables.create(u, b.path("name").asText(null), tempo(b)), u));
        });
        app.get("/api/tables/{id}", ctx -> {
            User u = Auth.user(ctx);
            TableManager.Table t = tables.get(ctx.pathParam("id")).orElse(null);
            if (t == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Diesen Tisch gibt es nicht mehr"));
                return;
            }
            ctx.json(view(t, u));
        });
        app.post("/api/tables/{id}/join", ctx -> {
            User u = Auth.user(ctx);
            TableManager.Table t = tables.join(u, ctx.pathParam("id"));
            onJoined.accept(u.id(), t.id);
            ctx.json(view(t, u));
        });
        app.post("/api/tables/{id}/leave", ctx -> {
            User u = Auth.user(ctx);
            boolean closed = tables.leave(u, ctx.pathParam("id"));
            ctx.json(Map.of("left", true, "closed", closed));
        });
        app.put("/api/tables/{id}/seat", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            ctx.json(view(tables.setMyDeck(u, ctx.pathParam("id"), b.get("deck")), u));
        });
        app.put("/api/tables/{id}/seats/{n}", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            TableManager.SeatKind kind = TableManager.SeatKind.valueOf(b.path("kind").asText("OPEN").toUpperCase(Locale.ROOT));
            ctx.json(view(tables.setSeat(u, ctx.pathParam("id"), Integer.parseInt(ctx.pathParam("n")), kind, b.get("deck")), u));
        });
        app.put("/api/tables/{id}", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            ctx.json(view(tables.update(u, ctx.pathParam("id"), b.path("name").asText(null), tempo(b)), u));
        });
        app.post("/api/tables/{id}/chat", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            ctx.json(view(tables.chat(u, ctx.pathParam("id"), b.path("text").asText("")), u));
        });
        app.post("/api/tables/{id}/start", ctx -> {
            User u = Auth.user(ctx);
            ctx.json(view(tables.start(u, ctx.pathParam("id")), u));
        });
    }

    private static JsonNode body(Context ctx) throws Exception {
        String s = ctx.body();
        return s == null || s.isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(s);
    }

    private static TempoSettings.Preset tempo(JsonNode b) {
        if (!b.hasNonNull("tempo")) {
            return null;
        }
        try {
            return TempoSettings.Preset.valueOf(b.get("tempo").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekanntes Tempo");
        }
    }

    /** Sicht eines Nutzers auf einen Tisch (eigener Platz, Gastgeber-Flag, Deck-Namen). */
    private Map<String, Object> view(TableManager.Table t, User me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("name", t.name);
        m.put("hostUserId", t.hostUserId);
        m.put("hostName", t.hostName);
        m.put("tempo", t.tempo.name());
        m.put("state", t.state);
        m.put("gameId", t.gameId);
        m.put("lastGameId", t.lastGameId);
        m.put("createdAt", t.createdAt);
        m.put("updatedAt", t.updatedAt);
        List<Map<String, Object>> seats = new ArrayList<>();
        int mySeat = -1;
        for (int i = 0; i < t.seats.length; i++) {
            TableManager.Seat s = t.seats[i];
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("kind", s.kind.name());
            if (s.kind == TableManager.SeatKind.HUMAN) {
                sm.put("userId", s.userId);
                sm.put("name", s.name);
                sm.put("me", s.userId == me.id());
                if (s.userId == me.id()) {
                    mySeat = i;
                }
            }
            if (s.kind != TableManager.SeatKind.OPEN) {
                // eigenes Deck als Spec (zum Weiterbearbeiten), fremde nur als Name
                boolean own = s.kind == TableManager.SeatKind.HUMAN ? s.userId == me.id() : t.hostUserId == me.id();
                if (own && s.deck != null) {
                    sm.put("deck", s.deck);
                }
                long owner = s.kind == TableManager.SeatKind.HUMAN ? s.userId : t.hostUserId;
                sm.put("deckName", s.deck == null ? null : decks.describe(owner, s.deck).orElse("?"));
                sm.put("ready", s.kind == TableManager.SeatKind.BOT || s.deck != null);
            }
            seats.add(sm);
        }
        m.put("seats", seats);
        m.put("mySeat", mySeat < 0 ? null : mySeat);
        m.put("host", t.hostUserId == me.id());
        m.put("humans", seats.stream().filter(s -> "HUMAN".equals(s.get("kind"))).count());
        List<Map<String, Object>> chat = new ArrayList<>();
        for (TableManager.ChatMsg c : t.chat) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("ts", c.ts());
            cm.put("userId", c.userId());
            cm.put("name", c.name());
            cm.put("text", c.text());
            chat.add(cm);
        }
        m.put("chat", chat);
        return m;
    }
}
