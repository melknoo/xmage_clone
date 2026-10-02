package dev.magelite.images;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kartenbilder von Scryfall mit lokalem Disk-Cache.
 * <ul>
 *   <li>{@code GET /img/card/{set}/{num}?size=&face=&name=}</li>
 *   <li>{@code GET /img/token?name=&set=&n=&size=}</li>
 *   <li>{@code GET /img/named?name=&size=}</li>
 * </ul>
 * Scryfall-API max. ~10 Anfragen/s (wir: 1 Anfrage pro 110 ms), CDN-Downloads ohne Limit.
 * Fehlschlaege werden 7 Tage gemerkt.
 */
public final class ImageService implements HttpServer.Module {

    private static final Logger LOG = Logger.getLogger(ImageService.class);
    private static final String API = "https://api.scryfall.com";
    private static final String UA = "MageLite/0.1 (Commander goldfish desktop app)";
    private static final long NEGATIVE_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final Set<String> SIZES = Set.of("small", "normal", "large", "art_crop", "png", "border_crop");

    private final Path root;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "img");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, CompletableFuture<Path>> inflight = new ConcurrentHashMap<>();
    private final Object apiLock = new Object();
    private long nextApiAt;

    public ImageService(Path root) {
        this.root = root;
    }

    @Override
    public void register(Javalin app) {
        app.get("/img/card/{set}/{num}", ctx -> {
            String set = ctx.pathParam("set").toLowerCase(Locale.ROOT);
            String num = ctx.pathParam("num");
            String size = size(ctx);
            boolean back = "back".equals(ctx.queryParam("face"));
            String name = ctx.queryParam("name");
            String key = "cards/" + safe(set) + "/" + safe(num) + "_" + (back ? "b" : "f") + "_" + size + ".jpg";
            serve(ctx, key, () -> fetchCard(set, num, size, back, name));
        });
        app.get("/img/token", ctx -> {
            String name = ctx.queryParam("name");
            String set = ctx.queryParam("set");
            String size = size(ctx);
            if (name == null || name.isBlank()) {
                ctx.status(404);
                return;
            }
            String clean = cleanTokenName(name);
            String key = "tokens/" + safe(set == null ? "_" : set.toLowerCase(Locale.ROOT)) + "/" + safe(clean) + "_" + size + ".jpg";
            serve(ctx, key, () -> fetchToken(clean, set, size));
        });
        app.get("/img/named", ctx -> {
            String name = ctx.queryParam("name");
            String size = size(ctx);
            if (name == null || name.isBlank()) {
                ctx.status(404);
                return;
            }
            String key = "named/" + safe(name) + "_" + size + ".jpg";
            serve(ctx, key, () -> fetchNamed(name, size));
        });
    }

    private static String size(Context ctx) {
        String s = ctx.queryParam("size");
        return s != null && SIZES.contains(s) ? s : "normal";
    }

    private interface Fetcher {
        byte[] fetch() throws Exception;
    }

    private void serve(Context ctx, String key, Fetcher fetcher) {
        Path file = root.resolve(key);
        if (Files.exists(file)) {
            send(ctx, file);
            return;
        }
        Path miss = root.resolve(key + ".404");
        try {
            if (Files.exists(miss) && System.currentTimeMillis() - Files.getLastModifiedTime(miss).toMillis() < NEGATIVE_TTL_MS) {
                ctx.status(404);
                return;
            }
        } catch (IOException ignored) {
            // weiter
        }
        CompletableFuture<Path> f = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                byte[] data = fetcher.fetch();
                if (data == null) {
                    Files.createDirectories(miss.getParent());
                    Files.writeString(miss, "");
                    return null;
                }
                Files.createDirectories(file.getParent());
                Path tmp = Files.createTempFile(file.getParent(), "dl", ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return file;
            } catch (Exception e) {
                LOG.warn("Bild " + key + " nicht ladbar: " + e);
                return null;
            } finally {
                inflight.remove(k);
            }
        }, pool));
        ctx.future(() -> f.thenAccept(p -> {
            if (p == null) {
                ctx.status(404);
            } else {
                send(ctx, p);
            }
        }));
    }

    private static void send(Context ctx, Path file) {
        try {
            ctx.contentType("image/jpeg");
            ctx.header("Cache-Control", "public, max-age=31536000, immutable");
            ctx.result(Files.readAllBytes(file));
        } catch (IOException e) {
            ctx.status(500);
        }
    }

    // ------------------------------------------------------------------ Scryfall

    private byte[] fetchCard(String set, String num, String size, boolean back, String name) throws Exception {
        String cn = transformNumber(num);
        String url = API + "/cards/" + enc(set) + "/" + enc(cn) + "?format=image&version=" + size + (back ? "&face=back" : "");
        byte[] data = apiGetBytes(url);
        if (data == null && name != null && !name.isBlank()) {
            // Set-Code/Nummer unbekannt bei Scryfall -> ueber den Namen
            data = fetchNamed(name, size);
        }
        return data;
    }

    private byte[] fetchNamed(String name, String size) throws Exception {
        String n = name.contains(" // ") ? name.substring(0, name.indexOf(" // ")) : name;
        return apiGetBytes(API + "/cards/named?exact=" + enc(n) + "&format=image&version=" + size);
    }

    private byte[] fetchToken(String name, String set, String size) throws Exception {
        String q = "t:token !\"" + name.replace("\"", "") + "\"";
        String url = API + "/cards/search?unique=art&order=released&q=" + enc(q);
        String json = apiGetString(url);
        if (json == null) {
            // Notloesung: Namenssuche ohne exakten Namen
            json = apiGetString(API + "/cards/search?unique=art&q=" + enc("t:token " + name.replace("\"", "")));
            if (json == null) {
                return null;
            }
        }
        JsonNode data = Json.MAPPER.readTree(json).path("data");
        if (!data.isArray() || data.isEmpty()) {
            return null;
        }
        JsonNode pick = data.get(0);
        if (set != null && !set.isBlank()) {
            String want = "t" + set.toLowerCase(Locale.ROOT);
            for (JsonNode c : data) {
                String s = c.path("set").asText();
                if (s.equals(want) || s.equals(set.toLowerCase(Locale.ROOT))) {
                    pick = c;
                    break;
                }
            }
        }
        String img = pick.path("image_uris").path(size).asText(null);
        if (img == null && pick.path("card_faces").isArray() && !pick.path("card_faces").isEmpty()) {
            img = pick.path("card_faces").get(0).path("image_uris").path(size).asText(null);
        }
        return img == null ? null : cdnGet(img);
    }

    private byte[] apiGetBytes(String url) throws Exception {
        throttle();
        HttpResponse<byte[]> res = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() == 429) {
            Thread.sleep(1000);
            throttle();
            res = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        }
        if (res.statusCode() != 200) {
            return null;
        }
        String ct = res.headers().firstValue("content-type").orElse("");
        return ct.startsWith("image/") ? res.body() : null;
    }

    private String apiGetString(String url) throws Exception {
        throttle();
        HttpResponse<String> res = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? res.body() : null;
    }

    private byte[] cdnGet(String url) throws Exception {
        HttpResponse<byte[]> res = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        return res.statusCode() == 200 ? res.body() : null;
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "application/json;q=0.9,*/*;q=0.8")
                .GET().build();
    }

    private void throttle() throws InterruptedException {
        long wait;
        synchronized (apiLock) {
            long now = System.currentTimeMillis();
            long at = Math.max(now, nextApiAt);
            nextApiAt = at + 110;
            wait = at - now;
        }
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    /** XMage-Sammlernummern -> Scryfall (wie ScryfallApiCard.transformCardNumberFromXmageToScryfall). */
    static String transformNumber(String num) {
        String n = num;
        if (n.endsWith("*")) {
            n = n.substring(0, n.length() - 1) + "★";
        }
        if (n.endsWith("+")) {
            n = n.substring(0, n.length() - 1) + "†";
        }
        if (n.endsWith("Ph")) {
            n = n.substring(0, n.length() - 2) + "Φ";
        }
        return n;
    }

    static String cleanTokenName(String name) {
        String n = name.replaceAll("(?i)\\s+token$", "").trim();
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 5) {
            n = n.substring(0, dot);
        }
        return n;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._★†Φ-]", "_");
    }
}
