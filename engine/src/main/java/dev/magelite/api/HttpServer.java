package dev.magelite.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.DeckStore;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.deck.SampleDeckCatalog;
import dev.magelite.game.GameHost;
import dev.magelite.game.GameRegistry;
import dev.magelite.game.GameSetup;
import dev.magelite.game.TempoSettings;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lokaler HTTP/WebSocket-Server (nur 127.0.0.1). REST fuer Decks/Spielstart, WebSocket fuer das Spiel.
 */
public final class HttpServer {

    private static final Logger LOG = Logger.getLogger(HttpServer.class);

    public record Config(int port, String token, Path uiDir, String version) {
    }

    /** Zusatzdienste, die spaeter angehaengt werden (Bilder, Stats, Profil). */
    public interface Module {
        void register(Javalin app);
    }

    private final Config config;
    private final GameRegistry games;
    private final DeckStore deckStore;
    private final SampleDeckCatalog samples;
    private final List<Module> modules = new ArrayList<>();
    private final Map<WsContext, Outbox> sockets = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private volatile Consumer<GameHost> onGameFinished = g -> {
    };
    private volatile GameStartListener onGameStarted = (host, deckId) -> {
    };
    private Javalin app;

    public interface GameStartListener {
        void started(GameHost host, Long humanDeckId);
    }

    public HttpServer(Config config, GameRegistry games, DeckStore deckStore, SampleDeckCatalog samples) {
        this.config = config;
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

    public int start() {
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, false));
            cfg.http.defaultContentType = "application/json";
            cfg.jetty.modifyWebSocketServletFactory(f -> f.setIdleTimeout(Duration.ofHours(2)));
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
            if (config.uiDir() != null && Files.isDirectory(config.uiDir())) {
                cfg.staticFiles.add(sf -> {
                    sf.directory = config.uiDir().toAbsolutePath().toString();
                    sf.location = Location.EXTERNAL;
                    sf.hostedPath = "/";
                });
                cfg.spaRoot.addFile("/", config.uiDir().resolve("index.html").toAbsolutePath().toString(), Location.EXTERNAL);
            }
        });

        app.before("/api/*", this::checkToken);
        app.before("/img/*", this::checkToken);
        app.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("API-Fehler " + ctx.path(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", String.valueOf(e.getMessage())));
        });

        app.get("/api/health", ctx -> ctx.json(Map.of("ok", true, "version", config.version())));
        app.get("/api/samples", ctx -> ctx.json(samples.list()));
        app.get("/api/decks", ctx -> ctx.json(deckStore.list()));
        app.get("/api/decks/{id}", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            DeckStore.StoredDeck d = deckStore.get(id).orElseThrow(() -> new IllegalArgumentException("Deck nicht gefunden"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deck", d);
            out.put("dck", deckStore.getDck(id).orElse(""));
            ctx.json(out);
        });
        app.delete("/api/decks/{id}", ctx -> ctx.json(Map.of("deleted", deckStore.delete(Long.parseLong(ctx.pathParam("id"))))));

        app.post("/api/games", this::createGame);
        app.get("/api/games/current", ctx -> {
            var cur = games.current();
            if (cur.isPresent()) {
                ctx.json(Map.of("gameId", cur.get().getId()));
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "kein Spiel"));
            }
        });

        app.ws("/ws/game/{id}", ws -> {
            ws.onConnect(ctx -> {
                if (!tokenOk(ctx.queryParam("token"))) {
                    ctx.closeSession(4401, "token");
                    return;
                }
                UUID id = UUID.fromString(ctx.pathParam("id"));
                var host = games.get(id);
                if (host.isEmpty()) {
                    ctx.closeSession(4404, "game");
                    return;
                }
                Outbox outbox = new Outbox(ctx);
                sockets.put(ctx, outbox);
                host.get().attach(outbox);
            });
            ws.onMessage(ctx -> onSocketMessage(ctx, ctx.message()));
            ws.onClose(ctx -> closeSocket(ctx));
            ws.onError(ctx -> closeSocket(ctx));
        });

        for (Module m : modules) {
            m.register(app);
        }

        app.start("127.0.0.1", config.port());
        return app.port();
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    // ------------------------------------------------------------------ Spiele

    private void createGame(Context ctx) throws Exception {
        JsonNode body = Json.MAPPER.readTree(ctx.body());
        JsonNode deckSpec = body.path("deck");
        Long humanDeckId = "user".equals(deckSpec.path("type").asText()) ? deckSpec.path("id").asLong() : null;
        LoadedDeck humanDeck = resolveDeck(deckSpec, null);

        List<LoadedDeck> bots = new ArrayList<>();
        List<String> usedSamples = new ArrayList<>();
        JsonNode botSpecs = body.path("bots");
        for (int i = 0; i < 3; i++) {
            JsonNode spec = botSpecs.isArray() && botSpecs.size() > i ? botSpecs.get(i) : Json.MAPPER.createObjectNode().put("type", "random");
            bots.add(resolveDeck(spec, usedSamples));
        }
        TempoSettings.Preset tempo = TempoSettings.Preset.valueOf(body.path("tempo").asText("NORMAL").toUpperCase(Locale.ROOT));
        String name = body.path("playerName").asText("Du");

        GameHost host = games.start(new GameSetup(name, humanDeck, bots, tempo, humanDeckId), onGameFinished);
        onGameStarted.started(host, humanDeckId);
        ctx.json(Map.of("gameId", host.getId()));
    }

    private LoadedDeck resolveDeck(JsonNode spec, List<String> usedSamples) throws Exception {
        String type = spec.path("type").asText("random");
        switch (type) {
            case "user" -> {
                long id = spec.path("id").asLong();
                DeckStore.StoredDeck d = deckStore.get(id).orElseThrow(() -> new IllegalArgumentException("Deck " + id + " nicht gefunden"));
                String dck = deckStore.getDck(id).orElseThrow();
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
            UUID id = UUID.fromString(ctx.pathParam("id"));
            GameHost host = games.get(id).orElse(null);
            if (host == null) {
                return;
            }
            switch (m.path("t").asText()) {
                case "respond" -> host.respond(m.path("id").asLong(), parseResponse(m));
                case "action" -> host.action(m.path("action").asText(), m.hasNonNull("data") ? m.get("data").asText() : null);
                case "tempo" -> host.setTempo(TempoSettings.Preset.valueOf(m.path("preset").asText("NORMAL").toUpperCase(Locale.ROOT)));
                case "autoPass" -> host.setAutoPass(m.path("on").asBoolean(true));
                case "autoPay" -> host.autoPayNow();
                case "settings" -> {
                    if (m.has("autoPay")) {
                        host.setAutoPayDefault(m.get("autoPay").asBoolean(true));
                    }
                    if (m.has("autoPass")) {
                        host.setAutoPass(m.get("autoPass").asBoolean(true));
                    }
                }
                case "leave" -> host.abort();
                case "ping" -> ctx.send("{\"t\":\"pong\"}");
                default -> LOG.debug("Unbekannte Nachricht: " + text);
            }
        } catch (Exception e) {
            LOG.warn("WS-Nachricht fehlerhaft: " + text, e);
        }
    }

    private static GameHost.Response parseResponse(JsonNode m) {
        if (m.hasNonNull("uuid")) {
            return GameHost.Response.ofUuid(UUID.fromString(m.get("uuid").asText()));
        }
        if (m.hasNonNull("bool")) {
            return GameHost.Response.ofBool(m.get("bool").asBoolean());
        }
        if (m.hasNonNull("int")) {
            return GameHost.Response.ofInt(m.get("int").asInt());
        }
        if (m.hasNonNull("str")) {
            return GameHost.Response.ofString(m.get("str").asText());
        }
        if (m.hasNonNull("mana")) {
            JsonNode mana = m.get("mana");
            UUID pid = mana.hasNonNull("playerId") ? UUID.fromString(mana.get("playerId").asText()) : null;
            return GameHost.Response.ofMana(pid, ManaType.valueOf(mana.path("type").asText().toUpperCase(Locale.ROOT)));
        }
        return GameHost.Response.ofBool(false);
    }

    private void closeSocket(WsContext ctx) {
        Outbox o = sockets.remove(ctx);
        if (o != null) {
            o.close();
            try {
                UUID id = UUID.fromString(ctx.pathParam("id"));
                games.get(id).ifPresent(h -> h.detach(o));
            } catch (Exception ignored) {
                // egal
            }
        }
    }

    // ------------------------------------------------------------------ Auth

    private void checkToken(Context ctx) {
        if ("OPTIONS".equals(ctx.method().name())) {
            return;
        }
        String t = ctx.header("X-MageLite-Token");
        if (t == null) {
            t = ctx.queryParam("token");
        }
        if (!tokenOk(t)) {
            throw new io.javalin.http.UnauthorizedResponse("token");
        }
    }

    private boolean tokenOk(String t) {
        return config.token() == null || config.token().equals(t);
    }
}
