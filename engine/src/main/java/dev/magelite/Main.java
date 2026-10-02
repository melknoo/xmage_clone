package dev.magelite;

import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import dev.magelite.boot.CardDbManager;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.DeckRoutes;
import dev.magelite.deck.DeckStore;
import dev.magelite.deck.SampleDeckCatalog;
import dev.magelite.game.GameRegistry;
import dev.magelite.images.ImageService;
import dev.magelite.stats.Db;
import dev.magelite.stats.GameRecorder;
import dev.magelite.stats.ProfileService;
import dev.magelite.stats.StatsRoutes;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Engine-Host. Wird von Electron gestartet (oder per {@code gradlew run} im Dev-Modus).
 * <p>
 * Args: {@code --port=0 --data=<dir> --vendor=<dir> --ui=<dist> --parent-pid=<pid> --dev}
 * Das Arbeitsverzeichnis muss {@code <data>} sein (XMage oeffnet {@code ./db/cards.h2}).
 * Meldet sich auf stdout mit {@code MAGELITE_READY {"port":..,"token":..}}.
 */
public final class Main {

    public static final String VERSION = "0.1.0";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, String> opt = parseArgs(args);
        Path data = Path.of(opt.getOrDefault("data", ".")).toAbsolutePath().normalize();
        Path vendor = Path.of(opt.getOrDefault("vendor", System.getProperty("magelite.vendor", "../../vendor/xmage"))).toAbsolutePath().normalize();
        boolean dev = opt.containsKey("dev");
        Files.createDirectories(data.resolve("logs"));
        LogConfig.configure(data.resolve("logs"), dev);
        Logger log = Logger.getLogger(Main.class);

        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (!cwd.equals(data)) {
            log.warn("Arbeitsverzeichnis (" + cwd + ") != data (" + data + ") - Karten-DB liegt unter " + cwd.resolve("db"));
        }

        CardDbManager.ensure(vendor.resolve("db/cards.h2.mv.db"));

        Db db = new Db(data.resolve("magelite.db"));
        DeckStore deckStore = new DeckStore(db);
        SampleDeckCatalog samples = new SampleDeckCatalog(vendor.resolve("sample-decks"));
        GameRegistry games = new GameRegistry();
        ProfileService profile = new ProfileService(db);
        GameRecorder recorder = new GameRecorder(db, profile);
        games.setRewardHook(recorder::record);

        String token = dev ? null : randomToken();
        Path ui = opt.containsKey("ui") ? Path.of(opt.get("ui")) : null;
        HttpServer server = new HttpServer(new HttpServer.Config(Integer.parseInt(opt.getOrDefault("port", "0")), token, ui, VERSION),
                games, deckStore, samples);
        server.addModule(new ImageService(data.resolve("cache").resolve("images")));
        server.addModule(new DeckRoutes(deckStore));
        server.addModule(new StatsRoutes(db, profile));
        int port = server.start();

        // Sample-Katalog im Hintergrund vorbereiten
        Thread warmup = new Thread(() -> {
            try {
                samples.list();
            } catch (Exception e) {
                log.warn("Warmup fehlgeschlagen: " + e);
            }
        }, "warmup");
        warmup.setDaemon(true);
        warmup.setPriority(Thread.MIN_PRIORITY);
        warmup.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            games.shutdown();
            server.stop();
            db.close();
        }, "shutdown"));

        Optional.ofNullable(opt.get("parent-pid")).map(Long::parseLong).ifPresent(Main::watchParent);

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("port", port);
        ready.put("token", token);
        ready.put("bootMs", System.currentTimeMillis() - t0);
        System.out.println("MAGELITE_READY " + Json.write(ready));
        System.out.flush();
        log.info("MageLite-Engine bereit auf Port " + port + " in " + (System.currentTimeMillis() - t0) + " ms");
    }

    private static void watchParent(long pid) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
                Logger.getLogger(Main.class).warn("Elternprozess " + pid + " beendet - Engine stoppt");
                System.exit(0);
            }
        }, "parent-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                if (eq > 0) {
                    m.put(a.substring(2, eq), a.substring(eq + 1));
                } else {
                    m.put(a.substring(2), "true");
                }
            }
        }
        return m;
    }
}
