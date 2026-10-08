package dev.magelite.api;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Setup-Download fuer die Startseite (Server-Modus, oeffentlich ohne Login - siehe {@link Auth#filter}):
 * neueste {@code MageLite-Setup-<version>.exe} aus {@code <data>/downloads} (auf fly das Volume; hochgeladen von
 * {@code scripts/release.ps1 -Fly} per {@code fly ssh sftp put}).
 * <ul>
 *   <li>{@code GET /api/download/info} -> {@code {available, version, bytes, file}}</li>
 *   <li>{@code GET /api/download/file} -> die Datei (gestreamt), 3 Downloads pro Minute je IP</li>
 * </ul>
 */
public final class DownloadRoutes implements HttpServer.Module {

    private static final Logger LOG = Logger.getLogger(DownloadRoutes.class);
    private static final Pattern NAME = Pattern.compile("^MageLite-Setup-([0-9][0-9A-Za-z.\\-]*)\\.exe$");
    private static final long CACHE_MS = 60_000;
    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 60_000;

    private record Info(Path file, String version, long bytes, long at) {
    }

    private final Path dir;
    private volatile Info cached;
    private final Map<String, Deque<Long>> downloads = new ConcurrentHashMap<>();

    public DownloadRoutes(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("Download-Ordner: " + e);
        }
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/download/info", ctx -> {
            Optional<Info> i = latest();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("available", i.isPresent());
            if (i.isPresent()) {
                m.put("version", i.get().version());
                m.put("bytes", i.get().bytes());
                m.put("file", i.get().file().getFileName().toString());
            }
            ctx.json(m);
        });
        app.get("/api/download/file", ctx -> {
            Optional<Info> i = latest();
            if (i.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Kein Setup hinterlegt"));
                return;
            }
            if (limited(Auth.clientIp(ctx))) {
                ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(Map.of("error", "Zu viele Downloads – kurz warten"));
                return;
            }
            Path f = i.get().file();
            String name = f.getFileName().toString();
            ctx.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
            ctx.header("Content-Length", Long.toString(i.get().bytes()));
            ctx.header("Cache-Control", "no-store");
            ctx.contentType("application/octet-stream");
            InputStream in = Files.newInputStream(f);
            ctx.result(in);
            LOG.info("Setup-Download " + name + " an " + Auth.clientIp(ctx));
        });
    }

    private Optional<Info> latest() {
        Info c = cached;
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_MS) {
            return Optional.of(c);
        }
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.list(dir)) {
            Optional<Path> newest = s.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }));
            if (newest.isEmpty()) {
                cached = null;
                return Optional.empty();
            }
            Path p = newest.get();
            Matcher m = NAME.matcher(p.getFileName().toString());
            String version = m.matches() ? m.group(1) : "?";
            Info i = new Info(p, version, Files.size(p), now);
            cached = i;
            return Optional.of(i);
        } catch (IOException e) {
            LOG.warn("Download-Ordner lesen: " + e);
            return Optional.empty();
        }
    }

    private boolean limited(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = downloads.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= LIMIT) {
                return true;
            }
            q.addLast(now);
            return false;
        }
    }
}
