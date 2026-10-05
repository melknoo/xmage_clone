package dev.magelite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;
import io.javalin.http.UnauthorizedResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Login per Einladungscode, {@code /api/me} und die Admin-Verwaltung der Einladungen.
 */
public final class AuthRoutes implements HttpServer.Module {

    private static final int COOKIE_MAX_AGE = 365 * 24 * 3600;

    private final HttpServer.Config config;
    private final Auth auth;
    private final AccountService accounts;

    public AuthRoutes(HttpServer.Config config, Auth auth, AccountService accounts) {
        this.config = config;
        this.auth = auth;
        this.accounts = accounts;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/me", ctx -> ctx.json(me(Auth.user(ctx))));
        app.post("/api/auth/login", this::login);
        app.post("/api/auth/logout", ctx -> {
            ctx.removeCookie(Auth.COOKIE, "/");
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/admin/invites", ctx -> {
            requireAdmin(ctx);
            ctx.json(accounts.list());
        });
        app.post("/api/admin/invites", ctx -> {
            requireAdmin(ctx);
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            String name = b.path("name").asText("").strip();
            if (name.isEmpty() || name.length() > 24) {
                throw new IllegalArgumentException("Name: 1-24 Zeichen");
            }
            ctx.json(accounts.create(name));
        });
        app.post("/api/admin/invites/{id}/rotate", ctx -> {
            requireAdmin(ctx);
            long id = Long.parseLong(ctx.pathParam("id"));
            String code = accounts.rotate(id).orElseThrow(() -> new IllegalArgumentException("Konto nicht gefunden"));
            ctx.json(Map.of("id", id, "code", code));
        });
        app.delete("/api/admin/invites/{id}", ctx -> {
            requireAdmin(ctx);
            long id = Long.parseLong(ctx.pathParam("id"));
            if (id == Auth.user(ctx).id()) {
                throw new IllegalArgumentException("Das eigene Konto kann nicht entfernt werden");
            }
            ctx.json(Map.of("deleted", accounts.delete(id)));
        });
    }

    private void login(Context ctx) throws Exception {
        if (!config.server()) {
            ctx.json(me(User.LOCAL));
            return;
        }
        if (auth.loginRateLimited(Auth.clientIp(ctx))) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(Map.of("error", "Zu viele Versuche, bitte eine Minute warten"));
            return;
        }
        JsonNode b = Json.MAPPER.readTree(ctx.body());
        String code = b.path("code").asText("");
        User u = auth.resolve(code).orElseThrow(() -> new UnauthorizedResponse("Code unbekannt"));
        Cookie cookie = new Cookie(Auth.COOKIE, InviteCodes.normalize(code), "/", COOKIE_MAX_AGE,
                "https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto")), 0, true, null, null, SameSite.LAX);
        ctx.cookie(cookie);
        accounts.touch(u.id());
        ctx.json(me(u));
    }

    private void requireAdmin(Context ctx) {
        if (!Auth.user(ctx).admin()) {
            throw new ForbiddenResponse("admin");
        }
    }

    private Map<String, Object> me(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", config.server() ? "server" : "local");
        m.put("user", Map.of("id", u.id(), "name", u.name(), "admin", u.admin()));
        return m;
    }
}
