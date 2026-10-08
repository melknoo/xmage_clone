package dev.magelite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.admin.UptimeBudget;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;
import io.javalin.http.UnauthorizedResponse;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Login (Einladungscode oder E-Mail + Passwort), {@code /api/me}, Konto sichern/aendern und die Admin-Verwaltung
 * der Einladungen. Jeder Login erzeugt eine Session (Cookie {@link Auth#SESSION_COOKIE}).
 */
public final class AuthRoutes implements HttpServer.Module {

    private static final int COOKIE_MAX_AGE = 365 * 24 * 3600;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int PW_MIN = 8;
    private static final int PW_MAX = 200;

    private final HttpServer.Config config;
    private final Auth auth;
    private final AccountService accounts;
    /** Server-Modus: hat der Nutzer gerade seine Engine angebunden (Host-Link)? */
    private volatile java.util.function.LongPredicate hostLinked = uid -> false;

    public AuthRoutes(HttpServer.Config config, Auth auth, AccountService accounts) {
        this.config = config;
        this.auth = auth;
        this.accounts = accounts;
    }

    public void setHostLinked(java.util.function.LongPredicate p) {
        this.hostLinked = p;
    }

    @Override
    public void register(Javalin app) {
        app.exception(AccountService.Conflict.class, (e, ctx) -> ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage())));

        app.get("/api/me", ctx -> ctx.json(me(Auth.user(ctx), ctx.attribute(Auth.SESSION_ATTR))));
        app.post("/api/auth/login", this::login);
        app.post("/api/auth/logout", ctx -> {
            accounts.deleteSession(ctx.attribute(Auth.SESSION_ATTR));
            ctx.removeCookie(Auth.SESSION_COOKIE, "/");
            ctx.removeCookie(Auth.COOKIE, "/");
            ctx.json(Map.of("ok", true));
        });
        app.post("/api/auth/register", this::registerAccount);
        app.put("/api/auth/account", this::updateAccount);

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

    /** {@code {code}} (Gast) oder {@code {email, password}}; beides erzeugt eine Session. */
    private void login(Context ctx) throws Exception {
        if (!config.server()) {
            ctx.json(me(User.LOCAL, null));
            return;
        }
        if (auth.loginRateLimited(Auth.clientIp(ctx))) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(Map.of("error", "Zu viele Versuche, bitte eine Minute warten"));
            return;
        }
        JsonNode b = Json.MAPPER.readTree(ctx.body());
        User u;
        String via;
        if (b.hasNonNull("email")) {
            String email = normalizeEmail(b.path("email").asText(""));
            char[] pw = b.path("password").asText("").toCharArray();
            u = accounts.byCredentials(email, pw).orElseThrow(() -> new UnauthorizedResponse("Anmeldung fehlgeschlagen"));
            if (accounts.needsVerification(u.id())) {
                ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error", "Bitte erst die E-Mail bestätigen (Link in der Mail)", "unverified", true));
                return;
            }
            via = "password";
        } else {
            String code = b.path("code").asText("");
            u = auth.byCode(code).orElseThrow(() -> new UnauthorizedResponse("Code unbekannt"));
            via = "code";
        }
        String token = accounts.createSession(u.id(), via);
        ctx.cookie(sessionCookie(ctx, token));
        accounts.touch(u.id());
        ctx.json(me(u, Passwords.tokenHash(token)));
    }

    /** Konto sichern: E-Mail + Passwort fuer das angemeldete Gast-Konto. */
    private void registerAccount(Context ctx) throws Exception {
        if (!config.server()) {
            throw new IllegalArgumentException("Im lokalen Modus gibt es keine Konten");
        }
        User u = Auth.user(ctx);
        if (u.hasPassword()) {
            throw new AccountService.Conflict("Dieses Konto hat schon ein Passwort");
        }
        JsonNode b = Json.MAPPER.readTree(ctx.body());
        String email = validEmail(b.path("email").asText(""));
        char[] pw = validPassword(b.path("password").asText(""));
        accounts.setCredentials(u.id(), email, Passwords.hash(pw));
        ctx.json(me(u.withCredentials(email), ctx.attribute(Auth.SESSION_ATTR)));
    }

    /** E-Mail und/oder Passwort aendern; braucht das aktuelle Passwort. Passwortwechsel beendet andere Sessions. */
    private void updateAccount(Context ctx) throws Exception {
        if (!config.server()) {
            throw new IllegalArgumentException("Im lokalen Modus gibt es keine Konten");
        }
        if (auth.loginRateLimited(Auth.clientIp(ctx))) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(Map.of("error", "Zu viele Versuche, bitte eine Minute warten"));
            return;
        }
        User u = Auth.user(ctx);
        if (!u.hasPassword()) {
            throw new IllegalArgumentException("Erst ein Passwort setzen (Konto sichern)");
        }
        JsonNode b = Json.MAPPER.readTree(ctx.body());
        char[] current = b.path("current").asText("").toCharArray();
        String stored = accounts.passwordHash(u.id()).orElse(null);
        if (!Passwords.verify(current, stored)) {
            throw new UnauthorizedResponse("Aktuelles Passwort stimmt nicht");
        }
        String email = u.email();
        if (b.hasNonNull("email")) {
            email = validEmail(b.path("email").asText(""));
            if (!email.equalsIgnoreCase(u.email())) {
                if (!u.friend()) {
                    // die E-Mail ist bestaetigt und Login/Reset haengen daran; Aenderung bisher nur ueber den Admin
                    throw new IllegalArgumentException("Die E-Mail eines registrierten Kontos kann (noch) nicht geändert werden");
                }
                accounts.updateEmail(u.id(), email);
            }
        }
        if (b.hasNonNull("password")) {
            char[] pw = validPassword(b.path("password").asText(""));
            accounts.updatePassword(u.id(), Passwords.hash(pw));
            accounts.deleteOtherSessions(u.id(), ctx.attribute(Auth.SESSION_ATTR));
        }
        ctx.json(me(u.withCredentials(email), ctx.attribute(Auth.SESSION_ATTR)));
    }

    static Cookie sessionCookie(Context ctx, String token) {
        return new Cookie(Auth.SESSION_COOKIE, token, "/", COOKIE_MAX_AGE,
                "https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto")), 0, true, null, null, SameSite.LAX);
    }

    private static String normalizeEmail(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    static String validEmail(String s) {
        String email = normalizeEmail(s);
        if (email.isEmpty() || email.length() > 120 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Bitte eine gültige E-Mail-Adresse angeben");
        }
        return email;
    }

    static char[] validPassword(String s) {
        if (s == null || s.length() < PW_MIN || s.length() > PW_MAX) {
            throw new IllegalArgumentException("Passwort: mindestens " + PW_MIN + " Zeichen");
        }
        return s.toCharArray();
    }

    private void requireAdmin(Context ctx) {
        Auth.requireAdmin(ctx);
    }

    /** @param sessionHash Token-Hash der aktuellen Session (null: lokal oder altes Code-Cookie) */
    private Map<String, Object> me(User u, String sessionHash) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", config.server() ? "server" : "local");
        m.put("hostLink", config.server() && hostLinked.test(u.id()));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", u.id());
        user.put("name", u.name());
        user.put("admin", u.admin());
        user.put("email", u.email());
        user.put("hasPassword", u.hasPassword());
        user.put("tier", u.friend() ? User.FRIEND : User.PUBLIC);
        m.put("user", user);
        UptimeBudget budget = auth.budget();
        if (config.server() && budget != null) {
            UptimeBudget.Status st = budget.status();
            m.put("budget", Map.of("limited", !budget.counts(u), "resetsAt", st.resetsAt()));
        }
        Map<String, Object> session = null;
        if (config.server() && sessionHash != null) {
            session = accounts.sessionInfo(u.id(), sessionHash)
                    .<Map<String, Object>>map(si -> new LinkedHashMap<>(Map.of("since", si.since(), "via", si.via())))
                    .orElse(null);
        }
        m.put("session", session);
        return m;
    }
}
