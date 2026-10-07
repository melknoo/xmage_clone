package dev.magelite.api;

import dev.magelite.auth.AccountService;
import dev.magelite.auth.InviteCodes;
import dev.magelite.auth.Passwords;
import dev.magelite.auth.User;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anmeldung pro Anfrage.
 * <ul>
 *   <li>Lokal: Zufallstoken (Header/Query) wie bisher; der Nutzer ist immer {@link User#LOCAL}.</li>
 *   <li>Server: Session-Cookie {@value #SESSION_COOKIE} (Zufallstoken, SHA-256 in {@code sessions}); jeder Login
 *       (Code oder E-Mail/Passwort) erzeugt eine Session. Uebergangsweise gilt auch noch das alte Cookie
 *       {@value #COOKIE} mit dem Einladungscode. Ohne gueltiges Cookie sind nur {@code /api/health} und
 *       {@code /api/auth/login} erreichbar.</li>
 * </ul>
 */
public final class Auth {

    /** Legacy: Einladungscode im Cookie (vor den Sessions) */
    public static final String COOKIE = "ml_code";
    public static final String SESSION_COOKIE = "ml_sess";
    public static final String ATTR = "user";
    /** Request-Attribut: Hash des Session-Tokens (fuer Logout / "andere Sessions beenden") */
    public static final String SESSION_ATTR = "sessHash";
    private static final int LOGIN_LIMIT = 10;
    private static final long LOGIN_WINDOW_MS = 60_000;

    private final HttpServer.Config config;
    private final AccountService accounts;
    private final Map<String, Deque<Long>> loginAttempts = new ConcurrentHashMap<>();

    public Auth(HttpServer.Config config, AccountService accounts) {
        this.config = config;
        this.accounts = accounts;
    }

    /** Before-Handler fuer {@code /api/*} und {@code /img/*}. */
    public void filter(Context ctx) {
        if ("OPTIONS".equals(ctx.method().name())) {
            return;
        }
        if (!config.server()) {
            String t = ctx.header("X-MageLite-Token");
            if (t == null) {
                t = ctx.queryParam("token");
            }
            if (!tokenOk(t)) {
                throw new UnauthorizedResponse("token");
            }
            ctx.attribute(ATTR, User.LOCAL);
            return;
        }
        String p = ctx.path();
        if (p.equals("/api/health") || p.equals("/api/auth/login")) {
            return;
        }
        String sess = ctx.cookie(SESSION_COOKIE);
        User u = resolve(sess, ctx.cookie(COOKIE)).orElseThrow(() -> new UnauthorizedResponse("login"));
        ctx.attribute(ATTR, u);
        String sessHash = sess == null || sess.isBlank() ? null : Passwords.tokenHash(sess);
        ctx.attribute(SESSION_ATTR, sessHash);
        accounts.touch(u.id(), sessHash);
    }

    /** Nutzer der Anfrage; lokal immer Nutzer 1. */
    public static User user(Context ctx) {
        User u = ctx.attribute(ATTR);
        return u == null ? User.LOCAL : u;
    }

    /** Nutzer der Anfrage, wenn er Admin ist; sonst 403. */
    public static User requireAdmin(Context ctx) {
        User u = user(ctx);
        if (!u.admin()) {
            throw new ForbiddenResponse("admin");
        }
        return u;
    }

    /** Nutzer zu den Cookies (Server-Modus): Session zuerst, sonst Legacy-Code. Lokal immer {@link User#LOCAL}. */
    public Optional<User> resolve(String sessionToken, String code) {
        if (!config.server()) {
            return Optional.of(User.LOCAL);
        }
        if (sessionToken != null && !sessionToken.isBlank()) {
            Optional<User> u = accounts.bySession(Passwords.tokenHash(sessionToken));
            if (u.isPresent()) {
                return u;
            }
        }
        return byCode(code);
    }

    /** Konto zu einem Einladungscode (Login bzw. Legacy-Cookie). */
    public Optional<User> byCode(String code) {
        if (!config.server()) {
            return Optional.of(User.LOCAL);
        }
        if (code == null || InviteCodes.normalize(code).isEmpty()) {
            return Optional.empty();
        }
        return accounts.byHash(InviteCodes.hash(code));
    }

    public boolean tokenOk(String t) {
        return config.token() == null || config.token().equals(t);
    }

    /**
     * Origin-Pruefung fuer WebSocket-Upgrades im Server-Modus: der Host des Origin muss dem Host-Header entsprechen.
     * Im Dev-Modus (Vite-Proxy: Origin localhost:5173, Host 127.0.0.1:7317) nicht geprueft.
     */
    public boolean originOk(String origin, String hostHeader) {
        if (!config.server() || config.dev()) {
            return true;
        }
        if (origin == null || hostHeader == null) {
            return false;
        }
        try {
            URI o = URI.create(origin);
            String host = o.getHost();
            int port = o.getPort();
            String expect = port > 0 ? host + ":" + port : host;
            String got = hostHeader;
            if (got.endsWith(":443") && "https".equals(o.getScheme())) {
                got = got.substring(0, got.length() - 4);
            }
            return expect != null && expect.equalsIgnoreCase(got);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Login-Versuche pro IP begrenzen (10 pro Minute). @return true, wenn der Versuch abgelehnt werden soll */
    public boolean loginRateLimited(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = loginAttempts.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > LOGIN_WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= LOGIN_LIMIT) {
                return true;
            }
            q.addLast(now);
            return false;
        }
    }

    /** Client-IP: hinter fly steht sie im Header {@code Fly-Client-IP}. */
    public static String clientIp(Context ctx) {
        String ip = ctx.header("Fly-Client-IP");
        return ip != null && !ip.isBlank() ? ip : ctx.ip();
    }
}
