package dev.magelite.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.auth.User;
import dev.magelite.deck.DeckResolver;
import dev.magelite.game.GameHost;
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
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;

/**
 * REST fuer Lobby und Tische (Server-Modus). Die Lobby synchronisiert sich per Polling auf
 * {@code GET /api/tables/{id}} (ca. alle 1,5 s).
 * <p>
 * Antworten werden immer aus einer Momentaufnahme ({@link TableManager#snapshot}) gebaut - nie aus dem lebenden Tisch,
 * der sich unter der TableManager-Sperre aendert. Deck-Infos (DB) werden ausserhalb der Sperre nachgeschlagen.
 */
public final class TableRoutes implements HttpServer.Module {

    private final TableManager tables;
    private final DeckResolver decks;
    private volatile BiConsumer<Long, String> onJoined = (userId, tableId) -> {
    };
    /** hat der Gastgeber seine Engine angebunden (Host-Link)? */
    private volatile LongPredicate hostLinked = uid -> false;
    /** hat der Nutzer eine offene Einladung an den Tisch (ersetzt das Passwort)? */
    private volatile BiPredicate<Long, String> invited = (uid, tableId) -> false;

    public TableRoutes(TableManager tables, DeckResolver decks) {
        this.tables = tables;
        this.decks = decks;
    }

    /** Wird nach einem Beitritt aufgerufen (Nutzer, Tisch), z.B. um Einladungen zu erledigen. */
    public void setOnJoined(BiConsumer<Long, String> cb) {
        this.onJoined = cb;
    }

    public void setHostLinked(LongPredicate p) {
        this.hostLinked = p;
    }

    public void setInvited(BiPredicate<Long, String> p) {
        this.invited = p;
    }

    @Override
    public void register(Javalin app) {
        app.exception(TableManager.TableException.class, (e, ctx) -> ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage())));
        app.exception(TableManager.PasswordException.class, (e, ctx) -> ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error", e.getMessage(), "needPassword", true)));

        app.get("/api/tables", ctx -> {
            User u = Auth.user(ctx);
            List<Map<String, Object>> out = new ArrayList<>();
            for (TableManager.TableSnap t : tables.snapshots(u.id())) {
                out.add(view(t, u));
            }
            ctx.json(out);
        });
        app.get("/api/tables/mine", ctx -> {
            User u = Auth.user(ctx);
            var t = tables.mineSnapshot(u.id());
            if (t.isPresent()) {
                ctx.json(view(t.get(), u));
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "kein Tisch"));
            }
        });
        app.post("/api/tables", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            TableManager.Hosting hosting;
            try {
                hosting = TableManager.Hosting.valueOf(b.path("hosting").asText("SERVER").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("hosting: SERVER oder REMOTE");
            }
            reply(ctx, tables.create(u, b.path("name").asText(null), tempo(b), hosting, b.path("password").asText(null)).id, u);
        });
        app.get("/api/tables/{id}", ctx -> reply(ctx, ctx.pathParam("id"), Auth.user(ctx)));
        app.post("/api/tables/{id}/join", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            String tableId = ctx.pathParam("id");
            String id = tables.join(u, tableId, b.path("password").asText(null), invited.test(u.id(), tableId)).id;
            onJoined.accept(u.id(), id);
            reply(ctx, id, u);
        });
        app.post("/api/tables/{id}/leave", ctx -> {
            User u = Auth.user(ctx);
            boolean closed = tables.leave(u, ctx.pathParam("id"));
            ctx.json(Map.of("left", true, "closed", closed));
        });
        app.put("/api/tables/{id}/seat", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            reply(ctx, tables.setMyDeck(u, ctx.pathParam("id"), b.get("deck")).id, u);
        });
        app.put("/api/tables/{id}/seats/{n}", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            TableManager.SeatKind kind;
            try {
                kind = TableManager.SeatKind.valueOf(b.path("kind").asText("OPEN").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("kind: OPEN oder BOT");
            }
            reply(ctx, tables.setSeat(u, ctx.pathParam("id"), Integer.parseInt(ctx.pathParam("n")), kind, b.get("deck")).id, u);
        });
        app.put("/api/tables/{id}", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            reply(ctx, tables.update(u, ctx.pathParam("id"), b.path("name").asText(null), tempo(b)).id, u);
        });
        app.post("/api/tables/{id}/chat", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            reply(ctx, tables.chat(u, ctx.pathParam("id"), b.path("text").asText("")).id, u);
        });
        app.post("/api/tables/{id}/start", ctx -> {
            User u = Auth.user(ctx);
            reply(ctx, tables.start(u, ctx.pathParam("id")).id, u);
        });
    }

    private void reply(Context ctx, String id, User u) {
        TableManager.TableSnap t = tables.snapshot(id, u.id()).orElse(null);
        if (t == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Diesen Tisch gibt es nicht mehr"));
            return;
        }
        ctx.json(view(t, u));
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

    /**
     * Sicht eines Nutzers auf einen Tisch (eigener Platz, Gastgeber-Flag, Deck-Infos). {@code turn} nur bei RUNNING;
     * pro besetztem Platz {@code deckTitle}, {@code commander}, {@code colors}, {@code commanderSet}, {@code commanderNum}
     * (fehlen, solange kein Deck gewaehlt ist). Chat nur fuer Sitzende.
     */
    private Map<String, Object> view(TableManager.TableSnap t, User me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("hostUserId", t.hostUserId());
        m.put("hostName", t.hostName());
        m.put("tempo", t.tempo().name());
        m.put("state", t.state());
        m.put("gameId", t.gameId());
        m.put("lastGameId", t.lastGameId());
        m.put("createdAt", t.createdAt());
        m.put("updatedAt", t.updatedAt());
        boolean remote = t.hosting() == TableManager.Hosting.REMOTE;
        m.put("hosting", t.hosting().name());
        m.put("locked", t.locked());
        m.put("starting", t.starting());
        // REMOTE: nur sinnvoll, solange die Engine des Gastgebers angebunden ist
        m.put("hostLinkOk", !remote || hostLinked.test(t.hostUserId()));
        boolean running = "RUNNING".equals(t.state());
        if (running) {
            m.put("turn", t.turn());
            m.put("spectators", t.spectators());
        }
        List<Map<String, Object>> seats = new ArrayList<>();
        int mySeat = -1;
        for (int i = 0; i < t.seats().size(); i++) {
            TableManager.SeatSnap s = t.seats().get(i);
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("kind", s.kind().name());
            if (s.kind() == TableManager.SeatKind.HUMAN) {
                sm.put("userId", s.userId());
                sm.put("name", s.name());
                sm.put("me", s.userId() == me.id());
                if (s.userId() == me.id()) {
                    mySeat = i;
                }
            }
            if (s.kind() != TableManager.SeatKind.OPEN) {
                // eigenes Deck als Spec (zum Weiterbearbeiten), fremde nur als Anzeige-Infos
                boolean own = s.kind() == TableManager.SeatKind.HUMAN ? s.userId() == me.id() : t.hostUserId() == me.id();
                if (own && s.deck() != null) {
                    sm.put("deck", s.deck());
                }
                long owner = s.kind() == TableManager.SeatKind.HUMAN ? s.userId() : t.hostUserId();
                DeckResolver.DeckInfo info = s.deck() == null ? null : decks.info(owner, s.deck()).orElse(null);
                sm.put("deckName", s.deck() == null ? null : info == null ? "?" : info.deckName());
                if (info != null) {
                    putIf(sm, "deckTitle", info.title());
                    putIf(sm, "commander", info.commander());
                    putIf(sm, "colors", info.colors());
                    putIf(sm, "commanderSet", info.commanderSet());
                    putIf(sm, "commanderNum", info.commanderNum());
                }
                sm.put("ready", s.kind() == TableManager.SeatKind.BOT || s.deck() != null);
            }
            seats.add(sm);
        }
        m.put("seats", seats);
        m.put("mySeat", mySeat < 0 ? null : mySeat);
        // Zuschauen: laufendes Spiel, ich sitze an keinem Tisch, noch Platz (sonst 4409/4429 beim Verbinden)
        m.put("canSpectate", running && !remote && t.gameId() != null && mySeat < 0 && t.spectators() < GameHost.MAX_SPECTATORS
                && tables.mine(me.id()).isEmpty());
        m.put("host", t.hostUserId() == me.id());
        m.put("humans", t.humans());
        List<Map<String, Object>> chat = new ArrayList<>();
        for (TableManager.ChatMsg c : t.chat()) {
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

    private static void putIf(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
