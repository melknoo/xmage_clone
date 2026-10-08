package dev.magelite.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.auth.Limits;
import dev.magelite.auth.User;
import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.DeckStore;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.deck.SampleDeckCatalog;
import dev.magelite.game.GameHost;
import dev.magelite.game.GameRegistry;
import dev.magelite.game.GameSetup;
import dev.magelite.game.TempoSettings;
import dev.magelite.relay.RemoteGames;
import dev.magelite.spike.Scenarios;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.javalin.websocket.WsContext;
import mage.constants.ManaType;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HTTP/WebSocket-Server. REST fuer Decks/Spielstart, WebSocket fuer das Spiel.
 * <p>
 * Lokal: nur {@code 127.0.0.1}, Zufallstoken, Nutzer 1. Server-Modus ({@code --server}): {@code 0.0.0.0},
 * Anmeldung per Cookie (siehe {@link Auth}), jedes Spiel gehoert einem Nutzer.
 */
public final class HttpServer {

    private static final Logger LOG = Logger.getLogger(HttpServer.class);

    /**
     * @param host     Bind-Adresse ({@code 127.0.0.1} lokal, {@code 0.0.0.0} auf fly)
     * @param token    Zufallstoken (lokal, Release); null im Dev- und Server-Modus
     * @param server   Server-Modus (Cookie-Login, Konten)
     * @param dev      Dev-Engine (Szenarien erlaubt, keine Origin-Pruefung)
     */
    public record Config(int port, String host, String token, Path uiDir, String version, boolean server, boolean dev) {
    }

    /** Zusatzdienste, die spaeter angehaengt werden (Bilder, Stats, Profil, Konten). */
    public interface Module {
        void register(Javalin app);
    }

    /**
     * Eine WebSocket-Verbindung: Sitz-Verbindung ({@code seat} gesetzt) oder Zuschauer ({@code spectator}, kein Sitz).
     * {@code lastPing}: letzter Ping des Clients (Zuschauer ohne Ping ueber {@link #SPECTATOR_PING_TIMEOUT_MS} fliegen raus).
     */
    private record Session(Outbox outbox, long userId, GameHost host, GameHost.HumanSeat seat, boolean spectator, AtomicLong lastPing,
                           RemoteGames.RemoteGame remote) {
        Session(Outbox outbox, long userId, GameHost host, GameHost.HumanSeat seat) {
            this(outbox, userId, host, seat, false, new AtomicLong(System.currentTimeMillis()), null);
        }

        /** Sitz in einem Relay-Spiel (laeuft auf dem Rechner des Gastgebers, siehe {@link RemoteGames}) */
        static Session remote(Outbox outbox, long userId, RemoteGames.RemoteGame g) {
            return new Session(outbox, userId, null, null, false, new AtomicLong(System.currentTimeMillis()), g);
        }
    }

    /** Close-Codes beim Zuschauen ({@code ?spectate=1}); alle endgueltig (kein Reconnect). */
    public static final int CLOSE_REPLACED = 4000;
    public static final int CLOSE_NOT_ALLOWED = 4403;
    public static final int CLOSE_NOT_RUNNING = 4404;
    public static final int CLOSE_TOO_SLOW = 4408;
    public static final int CLOSE_SEATED = 4409;
    public static final int CLOSE_FULL = 4429;
    /** oeffentliches Konto, Monatsbudget erschoepft */
    public static final int CLOSE_BUDGET = 4503;
    /** Zuschauer ohne Ping so lange -> schliessen (die UI pingt alle 20 s; Jetty-Idle-Timeout sind 2 h) */
    private static final long SPECTATOR_PING_TIMEOUT_MS = 60_000;

    private final Config config;
    private final Auth auth;
    private final GameRegistry games;
    private final DeckStore deckStore;
    private final SampleDeckCatalog samples;
    private final List<Module> modules = new ArrayList<>();
    private final Map<WsContext, Session> sockets = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final long startedAt = System.currentTimeMillis();
    /** letzte Aktivitaet eines angemeldeten Nutzers; 0 = seit dem Start keine (nur anonyme Anfragen) */
    private volatile long lastActivity;
    private volatile Consumer<GameHost> onGameFinished = g -> {
    };
    private volatile GameStartListener onGameStarted = (host, deckId) -> {
    };
    /** Server-Modus: Name des Tisches, dessen laufendes Spiel die id ist (leer = nicht zuschaubar); lokal null */
    private volatile Function<UUID, Optional<String>> spectatePolicy;
    /** sitzt der Nutzer an irgendeinem Tisch? Dann kein Zuschauen (sonst verpasst er den Start seines Tisches) */
    private volatile java.util.function.LongPredicate seatedAtTable = uid -> false;
    /** Server-Modus: Spiele auf den Rechnern von Gastgebern (Host-Link); lokal null */
    private volatile RemoteGames remoteGames;
    private ScheduledExecutorService spectatorWatch;
    private Javalin app;

    public interface GameStartListener {
        void started(GameHost host, Long humanDeckId);
    }

    public HttpServer(Config config, Auth auth, GameRegistry games, DeckStore deckStore, SampleDeckCatalog samples) {
        this.config = config;
        this.auth = auth;
        this.games = games;
        this.deckStore = deckStore;
        this.samples = samples;
    }

    public void addModule(Module m) {
        modules.add(m);
    }

    public void setOnGameFinished(Consumer<GameHost> cb) {
        this.onGameFinished = cb;
    }

    public void setOnGameStarted(GameStartListener cb) {
        this.onGameStarted = cb;
    }

    /** Server-Modus: welche Spiele zuschaubar sind (laufende Tisch-Spiele -> Tischname). */
    public void setSpectatePolicy(Function<UUID, Optional<String>> policy, java.util.function.LongPredicate seatedAtTable) {
        this.spectatePolicy = policy;
        this.seatedAtTable = seatedAtTable;
    }

    /** Server-Modus: Relay-Spiele (Host-Link) fuer {@code /ws/game}, {@code /api/games/current} und die Belegung. */
    public void setRemoteGames(RemoteGames remoteGames) {
        this.remoteGames = remoteGames;
    }

    /**
     * Zeitpunkt der letzten API-Anfrage eines angemeldeten Nutzers (0 = noch keine seit dem Start); fuer den
     * Leerlauf-Exit im Server-Modus. Anonyme Anfragen (Startseite, Crawler) und abgelehnte zaehlen nicht.
     */
    public long lastActivity() {
        return lastActivity;
    }

    public long startedAt() {
        return startedAt;
    }

    /** Aktivitaet melden (Host-Link-Verkehr zaehlt wie eine API-Anfrage). */
    public void touch() {
        lastActivity = System.currentTimeMillis();
    }

    /** Laufende Spiele hier plus Relay-Spiele. */
    public int runningGames() {
        RemoteGames rg = remoteGames;
        return games.running() + (rg == null ? 0 : rg.running());
    }

    public int start() {
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, false));
            cfg.http.defaultContentType = "application/json";
            cfg.http.maxRequestSize = 2_000_000;
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setIdleTimeout(Duration.ofHours(2));
                // Host-Link: ganze States kommen inbound (zweistellige kB), Standard waeren 64 KiB
                f.setMaxTextMessageSize(4_000_000);
            });
            if (!config.server()) {
                cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
            }
            if (config.uiDir() != null && Files.isDirectory(config.uiDir())) {
                cfg.staticFiles.add(sf -> {
                    sf.directory = config.uiDir().toAbsolutePath().toString();
                    sf.location = Location.EXTERNAL;
                    sf.hostedPath = "/";
                });
                cfg.spaRoot.addFile("/", config.uiDir().resolve("index.html").toAbsolutePath().toString(), Location.EXTERNAL);
            }
        });

        app.before("/api/*", ctx -> {
            auth.filter(ctx);
            // erst nach dem Filter: nur angemeldete (und vom Budget zugelassene) Nutzer halten die Maschine wach
            User u = ctx.attribute(Auth.ATTR);
            if (u != null && auth.countsAsActivity(u)) {
                lastActivity = System.currentTimeMillis();
            }
        });
        app.before("/img/*", auth::filter);
        app.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage())));
        app.exception(GameRegistry.BusyException.class, (e, ctx) -> ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage(), "busy", true)));
        app.exception(Limits.BudgetExhausted.class, (e, ctx) -> ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of("error", e.getMessage(), "budget", true)));
        app.exception(Limits.ServerGamesForbidden.class, (e, ctx) -> ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error", e.getMessage(), "publicLimit", true)));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("API-Fehler " + ctx.path(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", String.valueOf(e.getMessage())));
        });

        app.get("/api/health", ctx -> ctx.json(Map.of("ok", true, "version", config.version(),
                "mode", config.server() ? "server" : "local", "games", runningGames())));
        app.get("/api/samples", ctx -> ctx.json(samples.list()));
        app.get("/api/decks", ctx -> ctx.json(deckStore.list(Auth.user(ctx).id())));
        app.get("/api/decks/{id}", ctx -> {
            long userId = Auth.user(ctx).id();
            long id = Long.parseLong(ctx.pathParam("id"));
            DeckStore.StoredDeck d = deckStore.get(userId, id).orElseThrow(() -> new IllegalArgumentException("Deck nicht gefunden"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deck", d);
            out.put("dck", deckStore.getDck(userId, id).orElse(""));
            ctx.json(out);
        });
        app.delete("/api/decks/{id}", ctx -> ctx.json(Map.of("deleted", deckStore.delete(Auth.user(ctx).id(), Long.parseLong(ctx.pathParam("id"))))));

        app.post("/api/games", this::createGame);
        app.get("/api/games/current", ctx -> {
            long uid = Auth.user(ctx).id();
            var cur = games.currentOf(uid);
            RemoteGames rg = remoteGames;
            var remote = rg == null ? Optional.<RemoteGames.RemoteGame>empty() : rg.currentOf(uid);
            if (cur.isPresent()) {
                ctx.json(Map.of("gameId", cur.get().getId()));
            } else if (remote.isPresent()) {
                ctx.json(Map.of("gameId", remote.get().id));
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "kein Spiel"));
            }
        });

        app.ws("/ws/game/{id}", ws -> {
            ws.onConnect(ctx -> {
                User user;
                if (config.server()) {
                    if (!auth.originOk(ctx.header("Origin"), ctx.header("Host"))) {
                        ctx.closeSession(4403, "origin");
                        return;
                    }
                    user = auth.resolve(ctx.cookie(Auth.SESSION_COOKIE), ctx.cookie(Auth.COOKIE)).orElse(null);
                    if (user == null) {
                        ctx.closeSession(4401, "login");
                        return;
                    }
                } else {
                    if (!auth.tokenOk(ctx.queryParam("token"))) {
                        ctx.closeSession(4401, "token");
                        return;
                    }
                    user = User.LOCAL;
                }
                UUID id;
                try {
                    id = UUID.fromString(ctx.pathParam("id"));
                } catch (IllegalArgumentException e) {
                    ctx.closeSession(4404, "game");
                    return;
                }
                var host = games.get(id);
                if (host.isEmpty()) {
                    RemoteGames rg = remoteGames;
                    RemoteGames.RemoteGame remote = rg == null ? null : rg.get(id).orElse(null);
                    if (remote == null) {
                        ctx.closeSession(4404, "game");
                        return;
                    }
                    // Relay-Spiel: kein Zuschauen (v1), Sitz nur fuer fly-Konten am Tisch; Verkehr geht 1:1 an den Host
                    if (ctx.queryParam("spectate") != null || !remote.hasSeat(user.id())) {
                        ctx.closeSession(4403, "seat");
                        return;
                    }
                    lastActivity = System.currentTimeMillis();
                    Outbox outbox = new Outbox(ctx);
                    sockets.put(ctx, Session.remote(outbox, user.id(), remote));
                    rg.attachPlayer(remote, user.id(), outbox);
                    return;
                }
                if (ctx.queryParam("spectate") != null) {
                    connectSpectator(ctx, user, host.get(), id);
                    return;
                }
                GameHost.HumanSeat seat = host.get().seatOf(user.id()).orElse(null);
                if (seat == null) {
                    ctx.closeSession(4403, "seat");
                    return;
                }
                lastActivity = System.currentTimeMillis();
                Outbox outbox = new Outbox(ctx);
                sockets.put(ctx, new Session(outbox, user.id(), host.get(), seat));
                host.get().attach(seat, outbox);
            });
            ws.onMessage(ctx -> onSocketMessage(ctx, ctx.message()));
            ws.onClose(ctx -> closeSocket(ctx));
            ws.onError(ctx -> closeSocket(ctx));
        });

        for (Module m : modules) {
            m.register(app);
        }

        app.start(config.host(), config.port());
        spectatorWatch = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spectator-watch");
            t.setDaemon(true);
            return t;
        });
        spectatorWatch.scheduleWithFixedDelay(this::closeSilentSpectators, 10, 10, TimeUnit.SECONDS);
        return app.port();
    }

    public void stop() {
        if (spectatorWatch != null) {
            spectatorWatch.shutdownNow();
        }
        if (app != null) {
            app.stop();
        }
    }

    /**
     * Zuschauer-Verbindung ({@code ?spectate=1}). Reihenfolge der Ablehnungen: lokaler Modus 4403, sitzt selbst im Spiel
     * 4409 (nie den Sitz anhaengen - das wuerde dessen Verbindung stehlen), kein laufendes Tisch-Spiel 4404,
     * mehr als {@link GameHost#MAX_SPECTATORS} 4429. Eine neue Verbindung desselben Nutzers schliesst die alte mit 4000.
     */
    private void connectSpectator(WsContext ctx, User user, GameHost host, UUID gameId) {
        Function<UUID, Optional<String>> policy = spectatePolicy;
        if (!config.server() || policy == null) {
            ctx.closeSession(CLOSE_NOT_ALLOWED, "spectate");
            return;
        }
        if (!auth.countsAsActivity(user)) {
            ctx.closeSession(CLOSE_BUDGET, "budget");
            return;
        }
        if (host.seatOf(user.id()).isPresent() || seatedAtTable.test(user.id())) {
            ctx.closeSession(CLOSE_SEATED, "seated");
            return;
        }
        Optional<String> tableName = policy.apply(gameId);
        if (tableName.isEmpty() || !host.isSpectatable() || !host.isRunning()) {
            ctx.closeSession(CLOSE_NOT_RUNNING, "not running");
            return;
        }
        lastActivity = System.currentTimeMillis();
        Outbox outbox = new Outbox(ctx, true, () -> closeAsync(ctx, CLOSE_TOO_SLOW, "too slow"));
        Session session = new Session(outbox, user.id(), host, null, true, new AtomicLong(System.currentTimeMillis()), null);
        // vor dem Anmelden eintragen: Nachrichten des Clients (Ping) finden die Session sofort
        sockets.put(ctx, session);
        GameHost.SpectateResult res = host.attachSpectator(user.id(), user.name(), outbox, tableName.get());
        switch (res.status()) {
            case SEATED -> {
                sockets.remove(ctx);
                outbox.close();
                ctx.closeSession(CLOSE_SEATED, "seated");
            }
            case FULL -> {
                sockets.remove(ctx);
                outbox.close();
                ctx.closeSession(CLOSE_FULL, "full");
            }
            case OK -> {
                if (res.replaced() != null) {
                    for (Map.Entry<WsContext, Session> e : sockets.entrySet()) {
                        if (e.getValue().outbox() == res.replaced()) {
                            closeAsync(e.getKey(), CLOSE_REPLACED, "replaced");
                        }
                    }
                }
            }
        }
    }

    /** Schliesst einen Socket ausserhalb des aufrufenden Threads (nie den Game-Thread an Jetty blockieren lassen). */
    private void closeAsync(WsContext ctx, int code, String reason) {
        Session s = sockets.remove(ctx);
        if (s != null) {
            s.outbox().close();
            if (s.spectator()) {
                s.host().detachSpectator(s.outbox());
            }
        }
        Thread t = new Thread(() -> {
            try {
                ctx.closeSession(code, reason);
            } catch (Exception ignored) {
                // schon zu
            }
        }, "ws-close");
        t.setDaemon(true);
        t.start();
    }

    /** Zuschauer ohne Ping seit {@link #SPECTATOR_PING_TIMEOUT_MS}: tote Verbindungen geben ihren Platz frei. */
    private void closeSilentSpectators() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<WsContext, Session> e : sockets.entrySet()) {
                Session s = e.getValue();
                if (s.spectator() && now - s.lastPing().get() > SPECTATOR_PING_TIMEOUT_MS) {
                    LOG.info("Zuschauer ohne Ping - Verbindung wird geschlossen");
                    closeAsync(e.getKey(), CLOSE_TOO_SLOW, "no ping");
                }
            }
        } catch (Throwable e) {
            LOG.warn("Zuschauer-Wache: " + e);
        }
    }

    /** Schliesst alle WebSockets eines Nutzers (Einladung rotiert/entfernt) und beendet sein Spiel. */
    public void closeSessionsOf(long userId) {
        for (Map.Entry<WsContext, Session> e : sockets.entrySet()) {
            if (e.getValue().userId() == userId) {
                try {
                    e.getKey().closeSession(4401, "revoked");
                } catch (Exception ignored) {
                    // egal
                }
            }
        }
        games.abortOf(userId);
        RemoteGames rg = remoteGames;
        if (rg != null) {
            rg.abortOf(userId);
        }
    }

    /** Schliesst alle WebSockets (Leerlauf-Exit); die UI verbindet sich nach dem Neustart wieder. */
    public void closeAllSessions() {
        for (WsContext ctx : sockets.keySet()) {
            try {
                ctx.closeSession(4404, "idle");
            } catch (Exception ignored) {
                // egal
            }
        }
    }

    public int openSockets() {
        return sockets.size();
    }

    // ------------------------------------------------------------------ Spiele

    private void createGame(Context ctx) throws Exception {
        User user = Auth.user(ctx);
        Limits.requireServerGames(user, "Spiele gegen Bots laufen für dich in der App auf deinem Rechner");
        JsonNode body = Json.MAPPER.readTree(ctx.body());
        JsonNode deckSpec = body.path("deck");
        Long humanDeckId = "user".equals(deckSpec.path("type").asText()) ? deckSpec.path("id").asLong() : null;
        LoadedDeck humanDeck = resolveDeck(user.id(), deckSpec, null);

        TempoSettings.Preset tempo = TempoSettings.Preset.valueOf(body.path("tempo").asText("NORMAL").toUpperCase(Locale.ROOT));
        String name = body.path("playerName").asText(config.server() ? user.name() : "Du");
        // Test-Situationen (z.B. lange Trigger-Ketten) nur in der Dev-Engine, nie im Release oder auf dem Server
        String scenario = body.hasNonNull("scenario") ? body.get("scenario").asText() : null;
        if (scenario != null && (!config.dev() || !Scenarios.exists(scenario))) {
            throw new IllegalArgumentException("Szenario nicht erlaubt: " + scenario);
        }
        RemoteGames rg = remoteGames;
        if (rg != null) {
            rg.currentOf(user.id()).ifPresent(g -> {
                throw new GameRegistry.BusyException("Du spielst gerade am Tisch „" + g.tableName + "“ auf dem Rechner von " + g.hostName);
            });
        }

        List<GameSetup.SeatSpec> seatSpecs = new ArrayList<>();
        seatSpecs.add(GameSetup.SeatSpec.human(user.id(), name, humanDeck, humanDeckId));
        // Weitere Menschen (bis die Lobby da ist nur in der Dev-Engine): humans: [{userId, name?, deck?}]
        JsonNode extra = body.path("humans");
        if (extra.isArray() && extra.size() > 0) {
            if (!config.dev()) {
                throw new IllegalArgumentException("Mehrere Menschen pro Spiel nur ueber die Lobby");
            }
            for (JsonNode h : extra) {
                long uid = h.path("userId").asLong();
                JsonNode ds = h.has("deck") ? h.get("deck") : Json.MAPPER.createObjectNode().put("type", "random");
                Long did = "user".equals(ds.path("type").asText()) ? ds.path("id").asLong() : null;
                seatSpecs.add(GameSetup.SeatSpec.human(uid, h.path("name").asText("Spieler " + uid), resolveDeck(uid, ds, null), did));
            }
        }
        List<String> usedSamples = new ArrayList<>();
        JsonNode botSpecs = body.path("bots");
        for (int i = 0; seatSpecs.size() < 4; i++) {
            JsonNode spec = botSpecs.isArray() && botSpecs.size() > i ? botSpecs.get(i) : Json.MAPPER.createObjectNode().put("type", "random");
            seatSpecs.add(GameSetup.SeatSpec.bot(resolveDeck(user.id(), spec, usedSamples)));
        }

        GameHost host = games.start(new GameSetup(seatSpecs, tempo), onGameFinished,
                scenario == null ? null : h -> Scenarios.apply(scenario, h.getGame(), h.getHumanId()));
        onGameStarted.started(host, humanDeckId);
        ctx.json(Map.of("gameId", host.getId()));
    }

    private LoadedDeck resolveDeck(long userId, JsonNode spec, List<String> usedSamples) throws Exception {
        String type = spec.path("type").asText("random");
        switch (type) {
            case "user" -> {
                long id = spec.path("id").asLong();
                DeckStore.StoredDeck d = deckStore.get(userId, id).orElseThrow(() -> new IllegalArgumentException("Deck " + id + " nicht gefunden"));
                String dck = deckStore.getDck(userId, id).orElseThrow();
                return DeckLoader.fromDckText(dck, d.name(), "user:" + id);
            }
            case "sample" -> {
                String id = spec.path("id").asText();
                samples.find(id).orElseThrow(() -> new IllegalArgumentException("Sample-Deck nicht gefunden: " + id));
                if (usedSamples != null) {
                    usedSamples.add(id);
                }
                return DeckLoader.loadFile(samples.resolve(id));
            }
            default -> {
                List<SampleDeckCatalog.Entry> pool = new ArrayList<>(samples.list());
                if (usedSamples != null) {
                    pool.removeIf(e -> usedSamples.contains(e.id()));
                }
                while (!pool.isEmpty()) {
                    SampleDeckCatalog.Entry e = pool.remove(random.nextInt(pool.size()));
                    LoadedDeck d = DeckLoader.loadFile(samples.resolve(e.id()));
                    if (d.valid() || pool.isEmpty()) {
                        if (usedSamples != null) {
                            usedSamples.add(e.id());
                        }
                        return d;
                    }
                }
                throw new IllegalStateException("Keine Sample-Decks gefunden");
            }
        }
    }

    // ------------------------------------------------------------------ WebSocket

    private void onSocketMessage(WsContext ctx, String text) {
        try {
            JsonNode m = Json.MAPPER.readTree(text);
            Session s = sockets.get(ctx);
            if (s == null) {
                return;
            }
            if (s.spectator()) {
                // Zuschauer duerfen nur pingen; alles andere wird still verworfen (die UI schickt z.B. "settings")
                if ("ping".equals(m.path("t").asText())) {
                    s.lastPing().set(System.currentTimeMillis());
                    s.outbox().send(PONG);
                }
                return;
            }
            if (s.remote() != null) {
                RemoteGames rg = remoteGames;
                if (rg != null) {
                    rg.in(s.remote(), s.userId(), text, m);
                }
                return;
            }
            GameMessages.dispatch(s.host(), s.seat(), m, () -> ctx.send("{\"t\":\"pong\"}"));
        } catch (Exception e) {
            LOG.warn("WS-Nachricht fehlerhaft: " + text, e);
        }
    }

    private static final Map<String, String> PONG = Map.of("t", "pong");

    private void closeSocket(WsContext ctx) {
        Session s = sockets.remove(ctx);
        if (s != null) {
            s.outbox().close();
            try {
                if (s.remote() != null) {
                    RemoteGames rg = remoteGames;
                    if (rg != null) {
                        rg.detachPlayer(s.remote(), s.userId(), s.outbox());
                    }
                } else if (s.spectator()) {
                    s.host().detachSpectator(s.outbox());
                } else {
                    s.host().detach(s.seat(), s.outbox());
                }
            } catch (Exception ignored) {
                // egal
            }
        }
    }
}
