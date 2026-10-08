package dev.magelite.relay;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;

import java.util.Map;

/**
 * Lokale Steuerung des Host-Links (nur lokale Engine, Token-Auth wie alle {@code /api/*}): Electron meldet nach der
 * Anmeldung auf dem Server das Session-Cookie, die Engine verbindet sich ausgehend.
 * <ul>
 *   <li>{@code POST /api/host/link {server, session}} - verbinden (oder neu verbinden)</li>
 *   <li>{@code GET /api/host/link} - Zustand</li>
 *   <li>{@code DELETE /api/host/link} - trennen</li>
 * </ul>
 */
public final class HostLinkRoutes implements HttpServer.Module {

    private final HostLinkClient client;

    public HostLinkRoutes(HostLinkClient client) {
        this.client = client;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/host/link", ctx -> ctx.json(client.status()));
        app.post("/api/host/link", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            client.connect(b.path("server").asText(null), b.path("session").asText(null));
            ctx.json(client.status());
        });
        app.delete("/api/host/link", ctx -> {
            client.disconnect();
            ctx.json(Map.of("ok", true));
        });
        // eigenen Tisch auf dem Server verlassen/schliessen (Karte "Online spielen" der lokalen Startseite)
        app.post("/api/host/table/leave", ctx -> {
            String err = client.leaveTable();
            if (err != null) {
                throw new IllegalArgumentException(err);
            }
            ctx.json(client.status());
        });
        // Verbindung neu aufbauen, Spiele behalten (Test des Wiederanlaufs)
        app.post("/api/host/link/reconnect", ctx -> {
            client.reconnect();
            ctx.json(client.status());
        });
    }
}
