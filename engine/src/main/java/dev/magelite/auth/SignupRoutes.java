package dev.magelite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Oeffentliche Routen der Selbstregistrierung (Server-Modus, ohne Anmeldung erreichbar, siehe
 * {@link Auth#isPublicPath}): {@code GET /api/auth/options}, {@code POST /api/auth/signup|verify|resend|forgot|reset}.
 * Alle POSTs unterliegen dem Login-Rate-Limit pro IP. Lokal ist die Registrierung immer geschlossen.
 */
public final class SignupRoutes implements HttpServer.Module {

    private static final int NAME_MAX = 24;

    private final HttpServer.Config config;
    private final Auth auth;
    private final AccountService accounts;
    /** null im lokalen Modus */
    private final SignupService signup;

    public SignupRoutes(HttpServer.Config config, Auth auth, AccountService accounts, SignupService signup) {
        this.config = config;
        this.auth = auth;
        this.accounts = accounts;
        this.signup = signup;
    }

    @Override
    public void register(Javalin app) {
        app.exception(SignupService.Rejected.class, (e, ctx) -> ctx.status(e.status).json(Map.of("error", e.getMessage())));

        app.get("/api/auth/options", ctx -> {
            Map<String, Object> m = new LinkedHashMap<>();
            SignupService.State st = signup == null ? SignupService.State.CLOSED : signup.state();
            m.put("signup", st.name().toLowerCase(Locale.ROOT));
            m.put("turnstileSiteKey", signup == null ? null : signup.settings().turnstileSiteKey());
            m.put("forgot", signup != null && signup.mailAvailable());
            ctx.json(m);
        });
        app.post("/api/auth/signup", ctx -> {
            JsonNode b = body(ctx);
            String name = b.path("name").asText("").strip();
            if (name.isEmpty() || name.length() > NAME_MAX) {
                throw new IllegalArgumentException("Name: 1-" + NAME_MAX + " Zeichen");
            }
            String email = AuthRoutes.validEmail(b.path("email").asText(""));
            char[] pw = AuthRoutes.validPassword(b.path("password").asText(""));
            signup.signup(name, email, pw, b.path("captcha").asText(""), Auth.clientIp(ctx));
            ctx.json(Map.of("ok", true));
        });
        app.post("/api/auth/verify", ctx -> {
            JsonNode b = body(ctx);
            long id = signup.verify(b.path("token").asText(""));
            login(ctx, id, "verify");
        });
        app.post("/api/auth/resend", ctx -> {
            JsonNode b = body(ctx);
            signup.resend(AuthRoutes.validEmail(b.path("email").asText("")), b.path("captcha").asText(""), Auth.clientIp(ctx));
            ctx.json(Map.of("ok", true));
        });
        app.post("/api/auth/forgot", ctx -> {
            JsonNode b = body(ctx);
            signup.forgot(AuthRoutes.validEmail(b.path("email").asText("")), b.path("captcha").asText(""), Auth.clientIp(ctx));
            ctx.json(Map.of("ok", true));
        });
        app.post("/api/auth/reset", ctx -> {
            JsonNode b = body(ctx);
            char[] pw = AuthRoutes.validPassword(b.path("password").asText(""));
            long id = signup.reset(b.path("token").asText(""), pw);
            login(ctx, id, "reset");
        });
    }

    /** Server-Modus, Rate-Limit pro IP, JSON-Body. */
    private JsonNode body(Context ctx) throws Exception {
        if (!config.server() || signup == null) {
            throw new SignupService.Rejected(403, "Im lokalen Modus gibt es keine Konten");
        }
        if (auth.loginRateLimited(Auth.clientIp(ctx))) {
            throw new SignupService.Rejected(HttpStatus.TOO_MANY_REQUESTS.getCode(), "Zu viele Versuche, bitte eine Minute warten");
        }
        return Json.MAPPER.readTree(ctx.body());
    }

    /** Nach Bestaetigung/Reset direkt angemeldet (neue Session, Cookie); die UI laedt danach {@code /api/me}. */
    private void login(Context ctx, long userId, String via) {
        String token = accounts.createSession(userId, via);
        ctx.cookie(AuthRoutes.sessionCookie(ctx, token));
        accounts.touch(userId);
        ctx.json(Map.of("ok", true));
    }
}
