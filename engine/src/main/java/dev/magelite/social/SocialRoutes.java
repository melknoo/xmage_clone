package dev.magelite.social;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import dev.magelite.auth.User;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.Map;

/**
 * REST fuer Lobby-Chat, Freunde und Tisch-Einladungen (nur Server-Modus). Die UI pollt
 * {@code GET /api/social?after=<seq>} ausserhalb des Spiels (ca. alle 3 s).
 */
public final class SocialRoutes implements HttpServer.Module {

    private final SocialService social;

    public SocialRoutes(SocialService social) {
        this.social = social;
    }

    @Override
    public void register(Javalin app) {
        app.exception(SocialService.SocialException.class, (e, ctx) -> ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage())));

        app.get("/api/social", ctx -> {
            User u = Auth.user(ctx);
            long after = parseLong(ctx.queryParam("after"), 0);
            ctx.json(social.poll(u, after));
        });
        app.post("/api/social/chat", ctx -> {
            User u = Auth.user(ctx);
            ctx.json(social.say(u, body(ctx).path("text").asText("")));
        });
        app.put("/api/social/chat", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            if (!b.has("in")) {
                throw new IllegalArgumentException("in fehlt");
            }
            social.setIn(u, b.get("in").asBoolean());
            ctx.json(Map.of("in", b.get("in").asBoolean()));
        });
        app.delete("/api/social/invites/{id}", ctx -> {
            User u = Auth.user(ctx);
            social.decline(u, Long.parseLong(ctx.pathParam("id")));
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/friends", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            Long id = b.hasNonNull("userId") ? b.get("userId").asLong() : null;
            ctx.json(Map.of("state", social.request(u, b.path("name").asText(null), id)));
        });
        app.post("/api/friends/{id}/accept", ctx -> {
            User u = Auth.user(ctx);
            social.accept(u, Long.parseLong(ctx.pathParam("id")));
            ctx.json(Map.of("state", "friend"));
        });
        app.delete("/api/friends/{id}", ctx -> {
            User u = Auth.user(ctx);
            social.remove(u, Long.parseLong(ctx.pathParam("id")));
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/tables/{id}/invite", ctx -> {
            User u = Auth.user(ctx);
            JsonNode b = body(ctx);
            if (!b.hasNonNull("userId")) {
                throw new IllegalArgumentException("userId fehlt");
            }
            ctx.json(social.invite(u, ctx.pathParam("id"), b.get("userId").asLong()));
        });
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static JsonNode body(Context ctx) throws Exception {
        String s = ctx.body();
        return s == null || s.isBlank() ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(s);
    }
}
