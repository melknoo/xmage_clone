package dev.magelite.api;

import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Setup-Download fuer die Startseite (Server-Modus, oeffentlich ohne Login - siehe {@link Auth#isPublicPath}).
 * Die Datei liegt nicht auf fly (Egress, Volume), sondern als Release-Asset auf GitHub ({@code scripts/publish-setup.ps1});
 * der Server liefert nur den Link zur eigenen Version.
 * <ul>
 *   <li>{@code GET /api/download/info} -> {@code {available, version, url}}; {@code url} = {@code MAGELITE_DOWNLOAD_URL}
 *       mit {@code {v}} durch die Version ersetzt</li>
 * </ul>
 */
public final class DownloadRoutes implements HttpServer.Module {

    private final String urlTemplate;
    private final String version;

    /** @param urlTemplate z.B. {@code https://github.com/<owner>/<repo>/releases/download/v{v}/MageLite-Setup-{v}.exe}; null = kein Download */
    public DownloadRoutes(String urlTemplate, String version) {
        this.urlTemplate = urlTemplate == null || urlTemplate.isBlank() ? null : urlTemplate.strip();
        this.version = version;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/download/info", ctx -> {
            boolean available = urlTemplate != null && !"dev".equals(version);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("available", available);
            if (available) {
                m.put("version", version);
                m.put("url", urlTemplate.replace("{v}", version));
            }
            ctx.json(m);
        });
    }
}
