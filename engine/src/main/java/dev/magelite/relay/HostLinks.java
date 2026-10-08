package dev.magelite.relay;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import dev.magelite.api.Outbox;
import dev.magelite.auth.User;
import dev.magelite.stats.GameRecorder;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * fly-Seite des Host-Links: {@code /ws/host} (Server-Modus), eine ausgehende Verbindung je Gastgeber-Konto von der
 * Engine auf seinem Rechner ({@link HostLinkClient}). Auth wie {@code /ws/game} (Cookie + Origin).
 * <p>
 * Nachrichten Host -> fly: {@code started{spec} | error{tableId,msg} | out{g,u,m} | finished{result} |
 * resume{games[]} | ping}; fly -> Host: {@code start{spec} | attach{g,u} | detach{g,u} | in{g,u,m} | abort{g} | pong}.
 * {@code m} ist jeweils die unveraenderte Spielnachricht (Feld {@code t}).
 */
public final class HostLinks implements HttpServer.Module {

    private static final Logger LOG = Logger.getLogger(HostLinks.class);
    /** Host pingt alle 20 s; ohne Ping so lange -> Link gilt als tot */
    private static final long PING_TIMEOUT_MS = 90_000;
    private static final String PONG = "{\"t\":\"pong\"}";

    /** Ereignisse vom Host (siehe {@link RemoteGames}). Aufrufe auf dem Jetty-Thread des Links, kurz halten. */
    public interface Listener {
        void onOut(long hostUserId, UUID gameId, long userId, JsonNode message);

        void onFinished(long hostUserId, GameRecorder.GameResult result);

        void onResume(long hostUserId, List<RemoteGameSpec> games);

        void onLinkLost(long hostUserId);
    }

    private final class Link {
        final WsContext ctx;
        final User user;
        final Outbox out;
        final long since = System.currentTimeMillis();
        volatile long lastPing = since;
        /** offene Startanfragen je Tisch */
        final Map<String, CompletableFuture<RemoteGameSpec>> pending = new ConcurrentHashMap<>();

        Link(WsContext ctx, User user) {
            this.ctx = ctx;
            this.user = user;
            this.out = new Outbox(ctx);
        }
    }

    private final Auth auth;
    private final HttpServer.Config config;
    private final Runnable touch;
    private final Map<Long, Link> links = new ConcurrentHashMap<>();
    private volatile Listener listener = new Listener() {
        @Override
        public void onOut(long hostUserId, UUID gameId, long userId, JsonNode message) {
        }

        @Override
        public void onFinished(long hostUserId, GameRecorder.GameResult result) {
        }

        @Override
        public void onResume(long hostUserId, List<RemoteGameSpec> games) {
        }

        @Override
        public void onLinkLost(long hostUserId) {
        }
    };

    /** @param touch Aktivitaet melden (Leerlauf-Exit) */
    public HostLinks(Auth auth, HttpServer.Config config, Runnable touch) {
        this.auth = auth;
        this.config = config;
        this.touch = touch;
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "host-link-watch");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleWithFixedDelay(this::closeSilent, 15, 15, TimeUnit.SECONDS);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @Override
    public void register(Javalin app) {
        app.ws("/ws/host", ws -> {
            ws.onConnect(ctx -> {
                if (!config.server()) {
                    ctx.closeSession(4403, "server only");
                    return;
                }
                if (!auth.originOk(ctx.header("Origin"), ctx.header("Host"))) {
                    ctx.closeSession(4403, "origin");
                    return;
                }
                User user = auth.resolve(ctx.cookie(Auth.SESSION_COOKIE), ctx.cookie(Auth.COOKIE)).orElse(null);
                if (user == null) {
                    ctx.closeSession(4401, "login");
                    return;
                }
                Link link = new Link(ctx, user);
                ctx.attribute("link", link);
                Link old = links.put(user.id(), link);
                if (old != null) {
                    LOG.info("Host-Link von " + user.name() + " ersetzt");
                    closeQuiet(old.ctx, 4000, "replaced");
                }
                touch.run();
                LOG.info("Host-Link verbunden: " + user.name() + " (" + user.id() + ")");
            });
            ws.onMessage(ctx -> {
                Link link = ctx.attribute("link");
                if (link != null) {
                    handle(link, ctx.message());
                }
            });
            ws.onClose(ctx -> drop(ctx.attribute("link")));
            ws.onError(ctx -> drop(ctx.attribute("link")));
        });
    }

    private void drop(Link link) {
        if (link == null) {
            return;
        }
        link.out.close();
        if (links.remove(link.user.id(), link)) {
            LOG.info("Host-Link getrennt: " + link.user.name());
            for (CompletableFuture<RemoteGameSpec> f : link.pending.values()) {
                f.completeExceptionally(new IllegalStateException("Verbindung zum Rechner des Gastgebers abgebrochen"));
            }
            try {
                listener.onLinkLost(link.user.id());
            } catch (RuntimeException e) {
                LOG.warn("onLinkLost: " + e, e);
            }
        }
    }

    private void handle(Link link, String text) {
        try {
            JsonNode env = Json.MAPPER.readTree(text);
            long uid = link.user.id();
            switch (env.path("t").asText()) {
                case "ping" -> {
                    link.lastPing = System.currentTimeMillis();
                    link.out.send(new Outbox.Raw(PONG, false));
                }
                case "started" -> {
                    RemoteGameSpec spec = Json.MAPPER.treeToValue(env.get("spec"), RemoteGameSpec.class);
                    CompletableFuture<RemoteGameSpec> f = link.pending.remove(spec.tableId());
                    if (f != null) {
                        f.complete(spec);
                    } else {
                        LOG.warn("started ohne offene Anfrage: Tisch " + spec.tableId());
                    }
                }
                case "error" -> {
                    CompletableFuture<RemoteGameSpec> f = link.pending.remove(env.path("tableId").asText());
                    if (f != null) {
                        f.completeExceptionally(new IllegalStateException(env.path("msg").asText("Start auf dem Rechner des Gastgebers fehlgeschlagen")));
                    }
                }
                case "out" -> {
                    touch.run();
                    listener.onOut(uid, UUID.fromString(env.path("g").asText()), env.path("u").asLong(), env.get("m"));
                }
                case "finished" -> listener.onFinished(uid, Json.MAPPER.treeToValue(env.get("result"), GameRecorder.GameResult.class));
                case "resume" -> {
                    List<RemoteGameSpec> games = new ArrayList<>();
                    for (JsonNode n : env.path("games")) {
                        games.add(Json.MAPPER.treeToValue(n, RemoteGameSpec.class));
                    }
                    listener.onResume(uid, games);
                }
                default -> LOG.debug("Host-Link: unbekannte Nachricht " + env.path("t").asText());
            }
        } catch (Exception e) {
            LOG.warn("Host-Link-Nachricht fehlerhaft: " + e, e);
        }
    }

    private void closeSilent() {
        long now = System.currentTimeMillis();
        for (Link l : links.values()) {
            if (now - l.lastPing > PING_TIMEOUT_MS) {
                LOG.info("Host-Link ohne Ping - wird geschlossen: " + l.user.name());
                closeQuiet(l.ctx, 4408, "no ping");
            }
        }
    }

    private static void closeQuiet(WsContext ctx, int code, String reason) {
        Thread t = new Thread(() -> {
            try {
                ctx.closeSession(code, reason);
            } catch (Exception ignored) {
                // schon zu
            }
        }, "host-link-close");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ API fuer fly

    /** Hat das Konto gerade eine Engine angebunden? */
    public boolean has(long userId) {
        return links.containsKey(userId);
    }

    public Optional<String> hostName(long userId) {
        Link l = links.get(userId);
        return l == null ? Optional.empty() : Optional.of(l.user.name());
    }

    public int count() {
        return links.size();
    }

    /** Spiel auf dem Rechner des Gastgebers starten; erfuellt mit der Spec inkl. gameId/Spieler-ids. */
    public CompletableFuture<RemoteGameSpec> start(long hostUserId, RemoteGameSpec spec) {
        Link l = links.get(hostUserId);
        if (l == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Keine Verbindung zu deinem Rechner – MageLite-App starten und anmelden"));
        }
        CompletableFuture<RemoteGameSpec> f = new CompletableFuture<>();
        l.pending.put(spec.tableId(), f);
        l.out.send(Map.of("t", "start", "spec", spec));
        return f;
    }

    /** Nachricht an die Host-Engine (attach/detach/abort). @return false, wenn kein Link da ist */
    public boolean send(long hostUserId, Object msg) {
        Link l = links.get(hostUserId);
        if (l == null) {
            return false;
        }
        l.out.send(msg);
        return true;
    }

    /** Spielnachricht eines Spielers 1:1 an die Host-Engine. */
    public boolean sendIn(long hostUserId, UUID gameId, long userId, String rawJson) {
        Link l = links.get(hostUserId);
        if (l == null) {
            return false;
        }
        l.out.send(new Outbox.Raw("{\"t\":\"in\",\"g\":\"" + gameId + "\",\"u\":" + userId + ",\"m\":" + rawJson + "}", false));
        return true;
    }
}
