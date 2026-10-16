package dev.magelite.stats;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import dev.magelite.auth.User;
import dev.magelite.deck.DeckStore;
import dev.magelite.game.TempoSettings;
import dev.magelite.relay.HostLinks;
import dev.magelite.view.dto.Messages;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.apache.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Modus: {@code POST /api/games/report} - die Desktop-App meldet ein Solo-Spiel, das auf dem Rechner des
 * Nutzers lief, an sein Konto (Held, Meisterschaft, Statistik). Der Server vertraut den Spieldaten wie beim Host-Link,
 * legt aber Konto und Name selbst fest und uebernimmt die Deck-id nur, wenn es ein eigenes Deck ist.
 * <p>
 * Body {@code {result: GameResult}} mit genau einem Sitz; Antwort {@code {reward}} bzw. {@code {duplicate:true}}.
 * Nur mit Engine-Kennung ({@link HostLinks#ENGINE_HEADER}), sonst 426. Auch fuer oeffentliche Konten (die Rechnung lief
 * auf ihrem PC); bei erschoepftem Budget greift wie ueberall die 503 aus {@link Auth#filter}.
 */
public final class GameReportRoutes implements HttpServer.Module {

    private static final Logger LOG = Logger.getLogger(GameReportRoutes.class);
    /** Berichte je Konto und Minute (Nachholen aus der Warteschlange inklusive) */
    private static final int RATE_LIMIT = 30;
    private static final long RATE_WINDOW_MS = 60_000;
    private static final int MAX_BODY = 256 * 1024;

    private final GameRecorder recorder;
    private final DeckStore decks;
    private final Map<Long, Deque<Long>> recent = new ConcurrentHashMap<>();

    public GameReportRoutes(GameRecorder recorder, DeckStore decks) {
        this.recorder = recorder;
        this.decks = decks;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/games/report", this::report);
    }

    private void report(Context ctx) throws Exception {
        User u = Auth.user(ctx);
        if (!HostLinks.ENGINE_MARKER.equals(ctx.header(HostLinks.ENGINE_HEADER))) {
            ctx.status(HttpStatus.UPGRADE_REQUIRED).json(Map.of("error", "MageLite auf diesem Rechner aktualisieren"));
            return;
        }
        if (rateLimited(u.id())) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(Map.of("error", "Zu viele Spielberichte, bitte später nochmal"));
            return;
        }
        if (ctx.body().length() > MAX_BODY) {
            throw new IllegalArgumentException("Spielbericht zu groß");
        }
        JsonNode b = Json.MAPPER.readTree(ctx.body());
        if (!b.path("result").isObject()) {
            throw new IllegalArgumentException("result fehlt");
        }
        GameRecorder.GameResult r = Json.MAPPER.treeToValue(b.get("result"), GameRecorder.GameResult.class);
        GameRecorder.SeatResult seat = validate(r);
        Long deckId = seat.deckId() != null && decks.get(u.id(), seat.deckId()).isPresent() ? seat.deckId() : null;
        GameRecorder.SeatResult mine = seat.forUser(u.id(), u.name(), deckId);
        if (recorder.recorded(r.gameId(), u.id())) {
            ctx.json(Map.of("duplicate", true));
            return;
        }
        GameRecorder.Reward reward = recorder.record(r.onlySeat(mine), mine);
        if (reward == null) {
            if (recorder.recorded(r.gameId(), u.id())) {
                ctx.json(Map.of("duplicate", true));
            } else {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", "Spiel konnte nicht gespeichert werden"));
            }
            return;
        }
        LOG.info("Spielbericht von " + u.name() + " (" + u.id() + "): " + r.turns() + " Zuege, +" + reward.xpGained() + " XP");
        ctx.json(Map.of("reward", reward));
    }

    /** Form pruefen; liefert den einen Sitz. Fehler -> 400. */
    static GameRecorder.SeatResult validate(GameRecorder.GameResult r) {
        try {
            UUID.fromString(String.valueOf(r.gameId()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("gameId ungültig");
        }
        if (r.seats() == null || r.seats().size() != 1) {
            throw new IllegalArgumentException("Genau ein Sitz erwartet");
        }
        List<Messages.Placement> pl = r.placements();
        if (pl == null || pl.isEmpty() || pl.size() > 8) {
            throw new IllegalArgumentException("Platzierungen ungültig");
        }
        if (r.turns() < 0 || r.turns() > 5000 || r.durationMs() < 0 || r.durationMs() > 24L * 3600_000) {
            throw new IllegalArgumentException("Spieldauer ungültig");
        }
        try {
            TempoSettings.Preset.valueOf(String.valueOf(r.tempo()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tempo ungültig");
        }
        if ((r.deckNames() != null && r.deckNames().size() > 8) || (r.commanders() != null && r.commanders().size() > 8)) {
            throw new IllegalArgumentException("Zu viele Spieler");
        }
        GameRecorder.SeatResult s = r.seats().get(0);
        if (s.playerId() == null || pl.stream().noneMatch(p -> s.playerId().equals(p.playerId()))) {
            throw new IllegalArgumentException("Sitz passt nicht zu den Platzierungen");
        }
        if (s.humanTurns() < 0 || s.humanTurns() > r.turns() + 1) {
            throw new IllegalArgumentException("Züge ungültig");
        }
        if ((s.cards() != null && s.cards().size() > 400) || (s.commanders() != null && s.commanders().size() > 4)
                || (s.deckName() != null && s.deckName().length() > 200)) {
            throw new IllegalArgumentException("Sitzdaten zu groß");
        }
        return s;
    }

    private boolean rateLimited(long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > RATE_WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= RATE_LIMIT) {
                return true;
            }
            q.addLast(now);
            return false;
        }
    }
}
