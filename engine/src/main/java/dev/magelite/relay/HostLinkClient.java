package dev.magelite.relay;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.GameMessages;
import dev.magelite.api.Json;
import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.game.GameHost;
import dev.magelite.game.GameRegistry;
import dev.magelite.game.GameSetup;
import dev.magelite.game.TempoSettings;
import dev.magelite.stats.GameRecorder;
import dev.magelite.stats.StatsSink;
import dev.magelite.view.dto.Messages;
import org.apache.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Host-Seite des Host-Links (lokale Engine): eine ausgehende WebSocket-Verbindung zu {@code wss://<server>/ws/host},
 * angemeldet mit dem fly-Session-Cookie des Gastgebers. fly schickt {@code start} (Spiel mit Decks), danach haengen
 * sich die Spieler per {@code attach}/{@code in} an die hier laufenden {@link GameHost}s; Antworten gehen als
 * {@code out}-Umschlaege zurueck. Spielende: {@code finished} mit {@link GameRecorder.GameResult} (fly verbucht XP).
 * <p>
 * JDK-WebSocket: Text kommt fragmentiert ({@code last}) und darf nur sequenziell gesendet werden (ein Sender-Thread).
 * Eigener Ping alle {@link #PING_MS}; bleibt das Pong {@link #PONG_TIMEOUT_MS} aus, wird neu verbunden (Backoff 1-30 s).
 * Relay-Spiele verbuchen nichts in der lokalen DB ({@code RewardHook} null); die lokale UI sieht sie nicht
 * (Nutzer 1 hat keinen Sitz).
 */
public final class HostLinkClient {

    private static final Logger LOG = Logger.getLogger(HostLinkClient.class);
    private static final long PING_MS = 20_000;
    private static final long PONG_TIMEOUT_MS = 60_000;
    /** ohne Link so lange -> gehostete Spiele abbrechen (fly hat sie nach {@link RemoteGames#GRACE_MS} aufgegeben) */
    private static final long LINK_DOWN_ABORT_MS = 3 * 60_000L;
    /** ohne verbundenen Spieler so lange -> Spiel abbrechen */
    private static final long ORPHAN_MS = 10 * 60_000L;
    private static final String PONG = "{\"t\":\"pong\"}";

    public record GameInfo(UUID gameId, String tableName, int humans, int bots, int turn, int connected) {
    }

    /** Mein Tisch auf dem Server (aus {@code GET /api/tables/mine} mit der Session), null = keiner. */
    public record TableInfo(String id, String name, String state, int humans, boolean host, String hosting, boolean locked) {
    }

    /**
     * {@code userName}/{@code table}: Stand auf dem Server (nur solange verbunden; per Session abgefragt, 3 s Cache),
     * damit die lokale App zeigt, dass online noch ein Tisch offen ist.
     */
    public record Status(boolean enabled, boolean connected, String server, long since, String error, List<GameInfo> games,
                         String userName, TableInfo table) {
    }

    private final class Hosted {
        final GameHost host;
        volatile RemoteGameSpec spec;
        final Map<Long, RelaySink> sinks = new ConcurrentHashMap<>();

        Hosted(GameHost host, RemoteGameSpec spec) {
            this.host = host;
            this.spec = spec;
        }
    }

    private final GameRegistry games;
    private final Map<UUID, Hosted> hosted = new ConcurrentHashMap<>();
    private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "host-link-work"));
    private final Object lock = new Object();

    private volatile boolean enabled;
    private volatile String server;
    private volatile String session;
    private volatile WebSocket ws;
    private volatile boolean open;
    private volatile long since;
    private volatile long downSince;
    private volatile long lastPong;
    private volatile String lastError;
    private volatile CountDownLatch closed;
    private Thread loop;

    public HostLinkClient(GameRegistry games) {
        this.games = games;
        Thread sender = new Thread(this::sendLoop, "host-link-send");
        sender.setDaemon(true);
        sender.start();
        var ses = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "host-link-ping"));
        ses.scheduleWithFixedDelay(this::tick, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ------------------------------------------------------------------ Steuerung (lokale REST)

    /**
     * Verbindung (neu) aufbauen.
     *
     * @param server  Basis-URL des Servers, z.B. {@code https://magelite.fly.dev}
     * @param session Session-Token (Cookie {@code ml_sess}) des Gastgebers auf diesem Server
     */
    public void connect(String server, String session) {
        String base = server == null ? "" : server.strip().replaceAll("/+$", "");
        URI u = URI.create(base);
        if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https")) || u.getHost() == null) {
            throw new IllegalArgumentException("Server-URL: http(s)://host[:port]");
        }
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("Session fehlt");
        }
        synchronized (lock) {
            boolean same = enabled && base.equals(this.server) && session.equals(this.session);
            this.server = base;
            this.session = session;
            this.enabled = true;
            this.lastError = null;
            if (loop == null || !loop.isAlive()) {
                loop = daemon(this::runLoop, "host-link");
                loop.start();
            } else if (!same) {
                dropConnection();
            }
        }
    }

    /** Nur die Verbindung neu aufbauen (Spiele laufen weiter, fly spielt sie per resume/attach wieder ein); fuer Tests. */
    public void reconnect() {
        dropConnection();
    }

    /** Link trennen; laufende Relay-Spiele enden (fly meldet den Spielern den Abbruch). */
    public void disconnect() {
        synchronized (lock) {
            enabled = false;
            dropConnection();
        }
        for (Hosted h : hosted.values()) {
            h.host.abort();
        }
    }

    /**
     * Verbindung abrupt schliessen. {@code WebSocket.abort()} benachrichtigt den Listener nicht, deshalb den
     * Latch des Verbindungs-Loops selbst ausloesen (der Loop raeumt auf und verbindet ggf. neu).
     */
    private void dropConnection() {
        WebSocket cur = ws;
        if (cur != null) {
            cur.abort();
        }
        CountDownLatch l = closed;
        if (l != null) {
            l.countDown();
        }
    }

    public Status status() {
        List<GameInfo> list = new ArrayList<>();
        for (Hosted h : hosted.values()) {
            RemoteGameSpec s = h.spec;
            int connected = (int) h.host.seats().stream().filter(GameHost.HumanSeat::connected).count();
            list.add(new GameInfo(h.host.getId(), s.tableName(), s.humans(), s.seats().size() - s.humans(), h.host.currentTurn(), connected));
        }
        Remote r = open ? remote() : null;
        return new Status(enabled, open, server, since, lastError, list, r == null ? null : r.userName, r == null ? null : r.table);
    }

    private record Remote(String userName, TableInfo table, long at) {
    }

    private volatile Remote remoteCache;

    /** Konto-Name und eigener Tisch auf dem Server (per REST mit der Session; 3 s Cache, Fehler -> leer). */
    private Remote remote() {
        Remote c = remoteCache;
        long now = System.currentTimeMillis();
        if (c != null && now - c.at < 3000) {
            return c;
        }
        String name = null;
        TableInfo table = null;
        try {
            JsonNode me = serverGet("/api/me");
            if (me != null) {
                name = me.path("user").path("name").asText(null);
            }
            JsonNode t = serverGet("/api/tables/mine");
            if (t != null && t.hasNonNull("id")) {
                int humans = 0;
                for (JsonNode s : t.path("seats")) {
                    if ("HUMAN".equals(s.path("kind").asText())) {
                        humans++;
                    }
                }
                table = new TableInfo(t.path("id").asText(), t.path("name").asText(), t.path("state").asText("LOBBY"), humans,
                        t.path("host").asBoolean(false), t.path("hosting").asText("SERVER"), t.path("locked").asBoolean(false));
            }
        } catch (Exception e) {
            LOG.debug("Server-Status: " + e);
        }
        Remote r = new Remote(name, table, now);
        remoteCache = r;
        return r;
    }

    /** GET am Server mit der Session; null bei 404/Fehler. */
    private JsonNode serverGet(String path) throws Exception {
        java.net.http.HttpResponse<String> res = HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(8))
                        .header("Cookie", "ml_sess=" + session).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? Json.MAPPER.readTree(res.body()) : null;
    }

    /** Eigenen Tisch auf dem Server verlassen/schliessen (lokale App: "Tisch schliessen"). @return Fehlertext oder null */
    public String leaveTable() {
        if (!enabled || server == null || session == null) {
            return "Nicht mit dem Server verbunden";
        }
        try {
            JsonNode t = serverGet("/api/tables/mine");
            if (t == null || !t.hasNonNull("id")) {
                remoteCache = null;
                return null;
            }
            java.net.http.HttpResponse<String> res = HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(URI.create(server + "/api/tables/" + t.path("id").asText() + "/leave"))
                            .timeout(Duration.ofSeconds(8)).header("Cookie", "ml_sess=" + session)
                            .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            remoteCache = null;
            if (res.statusCode() != 200) {
                JsonNode b = Json.MAPPER.readTree(res.body());
                return b.path("error").asText("HTTP " + res.statusCode());
            }
            return null;
        } catch (Exception e) {
            return "Server nicht erreichbar: " + e.getMessage();
        }
    }

    public boolean isOpen() {
        return open;
    }

    // ------------------------------------------------------------------ Verbindung

    private void runLoop() {
        long backoff = 1000;
        while (enabled) {
            CountDownLatch latch = new CountDownLatch(1);
            closed = latch;
            try {
                String wsUrl = server.replaceFirst("^http", "ws") + "/ws/host";
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                WebSocket socket = client.newWebSocketBuilder()
                        .header("Cookie", "ml_sess=" + session)
                        .header("Origin", server)
                        .header(HostLinks.ENGINE_HEADER, HostLinks.ENGINE_MARKER)
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(wsUrl), new Listener(latch))
                        .join();
                ws = socket;
                open = true;
                since = System.currentTimeMillis();
                lastPong = since;
                downSince = 0;
                lastError = null;
                backoff = 1000;
                LOG.info("Host-Link verbunden: " + server);
                if (!hosted.isEmpty()) {
                    List<RemoteGameSpec> specs = hosted.values().stream().map(h -> h.spec).toList();
                    send(Map.of("t", "resume", "games", specs));
                }
                latch.await();
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                lastError = c.getMessage() == null ? c.toString() : c.getMessage();
                LOG.warn("Host-Link: " + lastError);
            } finally {
                open = false;
                ws = null;
                if (downSince == 0) {
                    downSince = System.currentTimeMillis();
                }
            }
            if (!enabled) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(30_000, backoff * 2);
        }
        LOG.info("Host-Link beendet");
    }

    private final class Listener implements WebSocket.Listener {
        private final CountDownLatch latch;
        private final StringBuilder buf = new StringBuilder();

        Listener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                try {
                    handle(text);
                } catch (Exception e) {
                    LOG.warn("Host-Link-Nachricht fehlerhaft: " + e, e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode == 4401) {
                lastError = "Anmeldung abgelaufen – in der App neu anmelden";
                enabled = false;
            } else if (statusCode == 4426) {
                lastError = "Der Server nutzt eine andere Engine – MageLite auf diesem Rechner aktualisieren";
                enabled = false;
            } else if (statusCode == 4000) {
                lastError = "Von einer neueren Verbindung ersetzt";
            } else {
                lastError = "Verbindung geschlossen (" + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason) + ")";
            }
            LOG.info("Host-Link geschlossen: " + statusCode + " " + reason);
            latch.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lastError = String.valueOf(error.getMessage());
            LOG.warn("Host-Link-Fehler: " + error);
            latch.countDown();
        }
    }

    private void sendLoop() {
        while (true) {
            String json;
            try {
                json = outbound.take();
            } catch (InterruptedException e) {
                return;
            }
            WebSocket socket = ws;
            if (socket == null || !open) {
                continue; // weg: fly spielt den Stand nach dem Reconnect per attach neu ein
            }
            try {
                socket.sendText(json, true).join();
            } catch (Exception e) {
                LOG.warn("Host-Link senden: " + e);
            }
        }
    }

    private void send(Object msg) {
        outbound.add(Json.write(msg));
    }

    void sendOut(UUID gameId, long userId, String json) {
        if (open) {
            outbound.add("{\"t\":\"out\",\"g\":\"" + gameId + "\",\"u\":" + userId + ",\"m\":" + json + "}");
        }
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            if (open) {
                if (now - lastPong > PONG_TIMEOUT_MS) {
                    LOG.warn("Host-Link: kein Pong seit " + (now - lastPong) / 1000 + " s - neu verbinden");
                    dropConnection();
                } else {
                    outbound.add("{\"t\":\"ping\"}");
                }
            } else if (enabled && downSince > 0 && now - downSince > LINK_DOWN_ABORT_MS && !hosted.isEmpty()) {
                LOG.warn("Host-Link seit " + (now - downSince) / 60_000 + " min weg - gehostete Spiele werden abgebrochen");
                for (Hosted h : hosted.values()) {
                    h.host.abort();
                }
            }
            for (Hosted h : hosted.values()) {
                if (h.host.isRunning() && h.host.disconnectedForMs() > ORPHAN_MS) {
                    LOG.warn("Relay-Spiel " + h.host.getId() + " ohne Spieler - wird abgebrochen");
                    h.host.abort();
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Host-Link-Wache: " + e, e);
        }
    }

    // ------------------------------------------------------------------ Nachrichten von fly

    private void handle(String text) throws Exception {
        JsonNode env = Json.MAPPER.readTree(text);
        switch (env.path("t").asText()) {
            case "pong" -> lastPong = System.currentTimeMillis();
            case "start" -> {
                RemoteGameSpec spec = Json.MAPPER.treeToValue(env.get("spec"), RemoteGameSpec.class);
                worker.execute(() -> startGame(spec));
            }
            case "attach" -> {
                Hosted h = hosted.get(UUID.fromString(env.path("g").asText()));
                long uid = env.path("u").asLong();
                if (h != null) {
                    h.host.seatOf(uid).ifPresent(seat -> {
                        RelaySink sink = h.sinks.computeIfAbsent(uid, k -> new RelaySink(this, h.host.getId(), uid));
                        h.host.attach(seat, sink);
                    });
                }
            }
            case "detach" -> {
                Hosted h = hosted.get(UUID.fromString(env.path("g").asText()));
                long uid = env.path("u").asLong();
                if (h != null) {
                    RelaySink sink = h.sinks.get(uid);
                    if (sink != null) {
                        h.host.seatOf(uid).ifPresent(seat -> h.host.detach(seat, sink));
                    }
                }
            }
            case "in" -> {
                UUID g = UUID.fromString(env.path("g").asText());
                Hosted h = hosted.get(g);
                long uid = env.path("u").asLong();
                if (h != null) {
                    h.host.seatOf(uid).ifPresent(seat -> GameMessages.dispatch(h.host, seat, env.path("m"), () -> sendOut(g, uid, PONG)));
                }
            }
            case "abort" -> {
                Hosted h = hosted.get(UUID.fromString(env.path("g").asText()));
                if (h != null) {
                    LOG.info("Relay-Spiel " + h.host.getId() + " von fly abgebrochen");
                    h.host.abort();
                }
            }
            default -> LOG.debug("Host-Link: unbekannte Nachricht " + env.path("t").asText());
        }
    }

    private void startGame(RemoteGameSpec spec) {
        try {
            List<GameSetup.SeatSpec> seats = new ArrayList<>();
            for (RemoteGameSpec.Seat s : spec.seats()) {
                if (s.dck() == null || s.dck().isBlank()) {
                    throw new IllegalArgumentException("Deck fehlt fuer " + s.name());
                }
                LoadedDeck deck = DeckLoader.fromDckText(s.dck(), s.deckName() == null ? "Deck" : s.deckName(), "relay:" + s.deckId());
                if (s.human()) {
                    if (s.userId() <= 1) {
                        throw new IllegalArgumentException("Ungueltiges Konto fuer " + s.name());
                    }
                    seats.add(GameSetup.SeatSpec.human(s.userId(), s.name(), deck, s.deckId()));
                } else {
                    seats.add(GameSetup.SeatSpec.bot(deck));
                }
            }
            TempoSettings.Preset tempo = TempoSettings.Preset.valueOf(spec.tempo() == null ? "NORMAL" : spec.tempo().toUpperCase(Locale.ROOT));
            // lokale Statistik bleibt unberuehrt: kein RewardHook (laeuft nach setRewardHook in GameRegistry.start)
            GameHost host = games.start(new GameSetup(seats, tempo), this::onFinished, h -> h.setRewardHook(null));
            List<RemoteGameSpec.Seat> started = new ArrayList<>();
            for (Messages.Seat ms : host.hello(host.firstSeat()).seats()) {
                GameHost.HumanSeat hs = host.seatOfPlayer(ms.playerId()).orElse(null);
                started.add(new RemoteGameSpec.Seat(ms.human(), hs == null ? 0 : hs.userId(), ms.name(), hs == null ? null : hs.deckId(),
                        ms.deckName(), null, ms.playerId(), ms.commanders()));
            }
            RemoteGameSpec full = spec.withGame(host.getId(), host.startedAt(), started);
            hosted.put(host.getId(), new Hosted(host, full));
            LOG.info("Relay-Spiel " + host.getId() + " fuer Tisch " + spec.tableId() + " gestartet (" + spec.humans() + " Menschen)");
            send(Map.of("t", "started", "spec", full));
        } catch (GameRegistry.BusyException e) {
            send(error(spec, "Auf dem Rechner des Gastgebers läuft gerade ein anderes Spiel"));
        } catch (Exception e) {
            LOG.warn("Relay-Start fehlgeschlagen: " + e, e);
            send(error(spec, "Start auf dem Rechner des Gastgebers fehlgeschlagen: " + e.getMessage()));
        }
    }

    private static Map<String, Object> error(RemoteGameSpec spec, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "error");
        m.put("tableId", spec.tableId());
        m.put("msg", msg);
        return m;
    }

    private void onFinished(GameHost host) {
        Hosted h = hosted.remove(host.getId());
        try {
            Messages.GameOver over = host.getGameOver();
            if (over == null) {
                over = new Messages.GameOver(null, "abgebrochen", List.of(), host.currentTurn(), 0, null, "Spiel ohne Ergebnis");
            }
            GameRecorder.GameResult result = GameRecorder.resultOf(host, over);
            send(Map.of("t", "finished", "result", result));
        } catch (RuntimeException e) {
            LOG.error("Relay-Spielende konnte nicht gemeldet werden", e);
        } finally {
            StatsSink.unregister(host.getId());
            if (h != null) {
                h.sinks.values().forEach(RelaySink::close);
            }
        }
    }
}
