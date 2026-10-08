package dev.magelite.relay;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Outbox;
import dev.magelite.stats.GameRecorder;
import dev.magelite.view.dto.Messages;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * fly-Seite: Spiele, die auf dem Rechner eines Gastgebers laufen (kein {@link dev.magelite.game.GameHost} hier).
 * fly kennt nur die Sitze, reicht Spielnachrichten zwischen den Spieler-WebSockets und dem Host-Link durch, verbucht
 * am Ende Statistik/XP je fly-Nutzer ({@link GameRecorder}) und schickt das {@code gameOver} mit Belohnung selbst.
 * <p>
 * Faellt der Host-Link aus, bleiben die Spieler-Verbindungen offen ({@link Messages.HostLink}); kommt er nicht binnen
 * {@link #GRACE_MS} zurueck, endet das Spiel mit Fehler (keine Statistik).
 */
public final class RemoteGames implements HostLinks.Listener {

    private static final Logger LOG = Logger.getLogger(RemoteGames.class);
    public static final long GRACE_MS = Long.getLong("magelite.hostGraceMs", 60_000L);
    /** beendete Spiele so lange behalten (spaete Verbindungen bekommen noch das gameOver) */
    private static final long KEEP_FINISHED_MS = 10 * 60_000L;
    private static final String PONG = "{\"t\":\"pong\"}";

    public record SeatView(long userId, String name, boolean connected, boolean conceded) {
    }

    public final class RemoteGame {
        public final UUID id;
        public final String tableId;
        public final String tableName;
        public final long hostUserId;
        public final String hostName;
        public final long startedAt;
        private volatile RemoteGameSpec spec;
        private volatile long hostLostSince;
        private volatile boolean finished;
        private volatile long finishedAt;
        private volatile int turn;
        private final Map<Long, Outbox> players = new ConcurrentHashMap<>();
        private final Set<Long> conceded = ConcurrentHashMap.newKeySet();
        private final Map<Long, Messages.GameOver> overByUser = new ConcurrentHashMap<>();

        RemoteGame(RemoteGameSpec spec) {
            this.id = spec.gameId();
            this.tableId = spec.tableId();
            this.tableName = spec.tableName();
            this.hostUserId = spec.hostUserId();
            this.hostName = spec.hostName();
            this.startedAt = spec.startedAt() > 0 ? spec.startedAt() : System.currentTimeMillis();
            this.spec = spec;
        }

        public RemoteGameSpec spec() {
            return spec;
        }

        public boolean hasSeat(long userId) {
            return spec.hasUser(userId);
        }

        public boolean running() {
            return !finished;
        }

        public boolean hostLost() {
            return hostLostSince > 0;
        }

        public int turn() {
            return turn;
        }

        public int bots() {
            return spec.seats().size() - spec.humans();
        }

        /** Sitz-Infos fuer den Admin (verbunden = Socket haengt dran). */
        public List<SeatView> seatViews() {
            List<SeatView> out = new ArrayList<>();
            for (RemoteGameSpec.Seat s : spec.seats()) {
                if (s.human()) {
                    out.add(new SeatView(s.userId(), s.name(), players.containsKey(s.userId()), conceded.contains(s.userId())));
                }
            }
            return out;
        }

        public String tempo() {
            return spec.tempo();
        }

        private void broadcast(Object msg) {
            for (Outbox o : players.values()) {
                o.send(msg);
            }
        }
    }

    private final HostLinks links;
    private final GameRecorder recorder;
    private final Map<UUID, RemoteGame> games = new ConcurrentHashMap<>();
    /** von fly abgebrochen (Host weg): ein spaeteres resume des Hosts darf sie nicht wiederbeleben */
    private final Set<UUID> aborted = ConcurrentHashMap.newKeySet();
    private volatile BiConsumer<String, UUID> onFinished = (table, game) -> {
    };

    public RemoteGames(HostLinks links, GameRecorder recorder) {
        this.links = links;
        this.recorder = recorder;
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-games-watch");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    /** (Tisch-id, Spiel-id) nach dem Ende - Tisch zurueck in die Lobby. */
    public void setOnFinished(BiConsumer<String, UUID> cb) {
        this.onFinished = cb;
    }

    // ------------------------------------------------------------------ Abfragen

    public Optional<RemoteGame> get(UUID id) {
        return Optional.ofNullable(games.get(id));
    }

    /** Laufendes Spiel, in dem der Nutzer noch mitspielt. */
    public Optional<RemoteGame> currentOf(long userId) {
        return games.values().stream().filter(g -> g.running() && g.hasSeat(userId) && !g.conceded.contains(userId)).findFirst();
    }

    public boolean inGame(long userId) {
        return currentOf(userId).isPresent();
    }

    public int running() {
        return (int) games.values().stream().filter(RemoteGame::running).count();
    }

    public List<RemoteGame> runningGames() {
        return games.values().stream().filter(RemoteGame::running).toList();
    }

    public int turnOf(UUID id) {
        RemoteGame g = games.get(id);
        return g == null ? 0 : g.turn;
    }

    // ------------------------------------------------------------------ Lebenszyklus

    /** Nach {@code started} vom Host: Spiel bekannt machen. */
    public RemoteGame register(RemoteGameSpec started) {
        RemoteGame g = new RemoteGame(started);
        games.put(g.id, g);
        LOG.info("Relay-Spiel " + g.id + " auf dem Rechner von " + g.hostName + " (" + started.humans() + " Menschen, " + g.bots() + " Bots)");
        return g;
    }

    /** Spieler-Socket haengt sich an; der Host spielt hello/state/prompt nach ({@code attach}). */
    public void attachPlayer(RemoteGame g, long userId, Outbox out) {
        g.players.put(userId, out);
        Messages.GameOver over = g.overByUser.get(userId);
        if (g.finished) {
            if (over != null) {
                out.send(over);
            }
            return;
        }
        links.send(g.hostUserId, Map.of("t", "attach", "g", g.id, "u", userId));
        if (g.hostLostSince > 0) {
            out.send(new Messages.HostLink(false, System.currentTimeMillis() - g.hostLostSince));
        }
    }

    public void detachPlayer(RemoteGame g, long userId, Outbox out) {
        if (g.players.remove(userId, out) && !g.finished) {
            links.send(g.hostUserId, Map.of("t", "detach", "g", g.id, "u", userId));
        }
    }

    /** Nachricht eines Spielers (schon geparst fuer {@code leave}/{@code ping}); alles andere geht 1:1 an den Host. */
    public void in(RemoteGame g, long userId, String rawJson, JsonNode parsed) {
        String t = parsed.path("t").asText();
        if ("ping".equals(t)) {
            Outbox o = g.players.get(userId);
            if (o != null) {
                o.send(new Outbox.Raw(PONG, false));
            }
            return;
        }
        if (g.finished) {
            return;
        }
        if ("leave".equals(t)) {
            g.conceded.add(userId);
        }
        links.sendIn(g.hostUserId, g.id, userId, rawJson);
    }

    /** Nutzer verlaesst alle Relay-Spiele (Konto entzogen). */
    public void abortOf(long userId) {
        for (RemoteGame g : games.values()) {
            if (g.running() && g.hasSeat(userId) && !g.conceded.contains(userId)) {
                g.conceded.add(userId);
                links.sendIn(g.hostUserId, g.id, userId, "{\"t\":\"leave\"}");
            }
        }
    }

    /** Admin: Spiel beenden. @return false, wenn unbekannt oder schon vorbei */
    public boolean abort(UUID id) {
        RemoteGame g = games.get(id);
        if (g == null || g.finished) {
            return false;
        }
        if (!links.send(g.hostUserId, Map.of("t", "abort", "g", id))) {
            abortLost(g, "Vom Admin beendet");
        }
        return true;
    }

    // ------------------------------------------------------------------ HostLinks.Listener

    @Override
    public void onOut(long hostUserId, UUID gameId, long userId, JsonNode message) {
        RemoteGame g = games.get(gameId);
        if (g == null || g.hostUserId != hostUserId || g.finished) {
            return;
        }
        boolean state = "state".equals(message.path("t").asText());
        if (state) {
            g.turn = message.path("turn").asInt(g.turn);
        }
        Outbox o = g.players.get(userId);
        if (o != null) {
            o.send(new Outbox.Raw(message.toString(), state));
        }
    }

    @Override
    public void onFinished(long hostUserId, GameRecorder.GameResult result) {
        UUID id;
        try {
            id = UUID.fromString(result.gameId());
        } catch (IllegalArgumentException e) {
            return;
        }
        RemoteGame g = games.get(id);
        if (g == null || g.hostUserId != hostUserId || g.finished) {
            return;
        }
        for (GameRecorder.SeatResult seat : result.seats()) {
            GameRecorder.Reward reward = null;
            try {
                reward = recorder.record(result, seat);
            } catch (RuntimeException e) {
                LOG.error("Belohnung fehlgeschlagen (" + seat.name() + ")", e);
            }
            Messages.GameOver over = result.gameOver(reward);
            g.overByUser.put(seat.userId(), over);
            Outbox o = g.players.get(seat.userId());
            if (o != null) {
                o.send(over);
            }
        }
        finish(g);
        LOG.info("Relay-Spiel " + g.id + " beendet (" + result.turns() + " Zuege)");
    }

    @Override
    public void onResume(long hostUserId, List<RemoteGameSpec> specs) {
        for (RemoteGameSpec spec : specs) {
            if (spec.gameId() == null || aborted.contains(spec.gameId())) {
                if (spec.gameId() != null) {
                    links.send(hostUserId, Map.of("t", "abort", "g", spec.gameId()));
                }
                continue;
            }
            RemoteGame g = games.get(spec.gameId());
            if (g == null) {
                if (spec.hostUserId() != hostUserId) {
                    continue;
                }
                // fly neu gestartet: Spiel ohne Tisch weiterfuehren
                g = register(spec);
            } else if (g.hostUserId != hostUserId) {
                continue;
            }
            g.spec = spec;
            boolean wasLost = g.hostLostSince > 0;
            g.hostLostSince = 0;
            if (wasLost) {
                g.broadcast(new Messages.HostLink(true, 0));
            }
            for (Long uid : g.players.keySet()) {
                links.send(hostUserId, Map.of("t", "attach", "g", g.id, "u", uid));
            }
        }
    }

    @Override
    public void onLinkLost(long hostUserId) {
        long now = System.currentTimeMillis();
        for (RemoteGame g : games.values()) {
            if (g.hostUserId == hostUserId && g.running() && g.hostLostSince == 0) {
                g.hostLostSince = now;
                g.broadcast(new Messages.HostLink(false, 0));
                LOG.info("Relay-Spiel " + g.id + ": Verbindung zum Gastgeber " + g.hostName + " verloren");
            }
        }
    }

    // ------------------------------------------------------------------ intern

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            for (RemoteGame g : games.values()) {
                if (!g.finished && g.hostLostSince > 0 && now - g.hostLostSince > GRACE_MS) {
                    abortLost(g, "Verbindung zum Gastgeber verloren");
                } else if (g.finished && now - g.finishedAt > KEEP_FINISHED_MS) {
                    games.remove(g.id);
                    aborted.remove(g.id);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Relay-Wache: " + e, e);
        }
    }

    /** Spiel ohne Host beenden: Fehler-GameOver an alle, keine Statistik. */
    private void abortLost(RemoteGame g, String reason) {
        if (g.finished) {
            return;
        }
        aborted.add(g.id);
        List<Messages.Placement> placements = new ArrayList<>();
        for (RemoteGameSpec.Seat s : g.spec.seats()) {
            placements.add(new Messages.Placement(s.playerId(), s.name(), 1, s.human(), 0, null, 0));
        }
        Messages.GameOver over = new Messages.GameOver(null, "abgebrochen", placements, g.turn,
                System.currentTimeMillis() - g.startedAt, null, reason);
        for (RemoteGameSpec.Seat s : g.spec.seats()) {
            if (s.human()) {
                g.overByUser.put(s.userId(), over);
            }
        }
        g.broadcast(over);
        finish(g);
        LOG.warn("Relay-Spiel " + g.id + " abgebrochen: " + reason);
    }

    private void finish(RemoteGame g) {
        g.finished = true;
        g.finishedAt = System.currentTimeMillis();
        try {
            onFinished.accept(g.tableId, g.id);
        } catch (RuntimeException e) {
            LOG.warn("onFinished: " + e, e);
        }
    }
}
