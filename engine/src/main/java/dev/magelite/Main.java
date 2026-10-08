package dev.magelite;

import dev.magelite.admin.AdminRoutes;
import dev.magelite.admin.AdminService;
import dev.magelite.api.Auth;
import dev.magelite.api.DownloadRoutes;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import dev.magelite.api.TableRoutes;
import dev.magelite.deck.DeckResolver;
import dev.magelite.game.TableManager;
import dev.magelite.social.FriendStore;
import dev.magelite.social.SocialRoutes;
import dev.magelite.social.SocialService;
import dev.magelite.auth.AccountService;
import dev.magelite.auth.AuthRoutes;
import dev.magelite.boot.CardDbManager;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.DeckRoutes;
import dev.magelite.deck.DeckStore;
import dev.magelite.deck.SampleDeckCatalog;
import dev.magelite.game.BotTuning;
import dev.magelite.game.GameHost;
import dev.magelite.game.GameRegistry;
import dev.magelite.images.ImageService;
import dev.magelite.relay.HostLinkClient;
import dev.magelite.relay.HostLinkRoutes;
import dev.magelite.relay.HostLinks;
import dev.magelite.relay.RemoteGameSpec;
import dev.magelite.relay.RemoteGames;
import dev.magelite.stats.Db;
import dev.magelite.stats.GameRecorder;
import dev.magelite.stats.ProfileService;
import dev.magelite.stats.StatsRoutes;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Engine-Host. Wird von Electron gestartet (oder per {@code gradlew run} im Dev-Modus), auf fly im Server-Modus.
 * <p>
 * Args: {@code --port=0 --data=<dir> --vendor=<dir> --ui=<dist> --parent-pid=<pid> --dev}
 * Server-Modus: {@code --server --host=0.0.0.0 --max-games=1 --idle-exit-min=10}; Owner-Konto aus
 * {@code MAGELITE_OWNER_CODE} / {@code MAGELITE_OWNER_NAME}.
 * Das Arbeitsverzeichnis muss {@code <data>} sein (XMage oeffnet {@code ./db/cards.h2}).
 * Meldet sich auf stdout mit {@code MAGELITE_READY {"port":..,"token":..}}.
 */
public final class Main {

    /** App-Version aus {@code desktop/package.json} (vom Build in {@code magelite-version.properties} geschrieben). */
    public static final String VERSION = loadVersion();

    private Main() {
    }

    private static String loadVersion() {
        try (InputStream in = Main.class.getResourceAsStream("/magelite-version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "").strip();
                if (!v.isEmpty() && !v.contains("$")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // Fallback unten
        }
        return "dev";
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, String> opt = parseArgs(args);
        Path data = Path.of(opt.getOrDefault("data", ".")).toAbsolutePath().normalize();
        Path vendor = Path.of(opt.getOrDefault("vendor", System.getProperty("magelite.vendor", "../../vendor/xmage"))).toAbsolutePath().normalize();
        boolean dev = opt.containsKey("dev");
        boolean server = opt.containsKey("server");
        String host = opt.getOrDefault("host", "127.0.0.1");
        int maxGames = Integer.parseInt(opt.getOrDefault("max-games", "1"));
        int idleExitMin = Integer.parseInt(opt.getOrDefault("idle-exit-min", "0"));
        Files.createDirectories(data.resolve("logs"));
        LogConfig.configure(data.resolve("logs"), dev || server); // Server: INFO auf stdout fuer `fly logs`
        Logger log = Logger.getLogger(Main.class);

        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (!cwd.equals(data)) {
            log.warn("Arbeitsverzeichnis (" + cwd + ") != data (" + data + ") - Karten-DB liegt unter " + cwd.resolve("db"));
        }

        CardDbManager.ensure(vendor.resolve("db/cards.h2.mv.db"));
        BotTuning.checkFfaEvaluator();

        Db db = new Db(data.resolve("magelite.db"));
        DeckStore deckStore = new DeckStore(db);
        SampleDeckCatalog samples = new SampleDeckCatalog(vendor.resolve("sample-decks"));
        GameRegistry games = new GameRegistry(server ? maxGames : 1);
        ProfileService profile = new ProfileService(db);
        GameRecorder recorder = new GameRecorder(db, profile);
        games.setRewardHook(recorder::record);
        AccountService accounts = new AccountService(db);

        if (server) {
            String ownerCode = System.getenv("MAGELITE_OWNER_CODE");
            if (ownerCode == null || ownerCode.isBlank()) {
                log.error("Server-Modus ohne MAGELITE_OWNER_CODE - niemand koennte sich anmelden. Abbruch.");
                System.exit(2);
            }
            accounts.ensureOwner(ownerCode, Optional.ofNullable(System.getenv("MAGELITE_OWNER_NAME")).filter(s -> !s.isBlank()).orElse("Owner"));
        }

        String token = (dev || server) ? null : randomToken();
        Path ui = opt.containsKey("ui") ? Path.of(opt.get("ui")) : null;
        HttpServer.Config cfg = new HttpServer.Config(Integer.parseInt(opt.getOrDefault("port", "0")), host, token, ui, VERSION, server, dev);
        Auth auth = new Auth(cfg, accounts);
        HttpServer httpServer = new HttpServer(cfg, auth, games, deckStore, samples);
        accounts.setOnRevoke(httpServer::closeSessionsOf);
        AuthRoutes authRoutes = new AuthRoutes(cfg, auth, accounts);
        httpServer.addModule(authRoutes);
        httpServer.addModule(new ImageService(data.resolve("cache").resolve("images")));
        DeckRoutes deckRoutes = new DeckRoutes(deckStore);
        httpServer.addModule(deckRoutes);
        deckRoutes.backfillBrackets();
        httpServer.addModule(new StatsRoutes(db, profile));
        HostLinkClient hostLink = null;
        if (server) {
            // Host-Link: Engines von Gastgebern haengen sich an (/ws/host); ihre Spiele laufen dort, fly reicht nur durch
            HostLinks hostLinks = new HostLinks(auth, cfg, httpServer::touch);
            RemoteGames remoteGames = new RemoteGames(hostLinks, recorder);
            hostLinks.setListener(remoteGames);
            httpServer.setRemoteGames(remoteGames);
            httpServer.addModule(hostLinks);
            authRoutes.setHostLinked(hostLinks::has);
            // Lobby/Tische: nur online sinnvoll (lokal startet man direkt)
            DeckResolver deckResolver = new DeckResolver(deckStore, samples);
            TableManager tableManager = new TableManager(games, deckResolver);
            tableManager.setRemote(remoteTables(hostLinks, remoteGames));
            remoteGames.setOnFinished(tableManager::onRemoteFinished);
            SocialService social = new SocialService(new FriendStore(db), tableManager, games);
            social.setAlsoInGame(remoteGames::inGame);
            TableRoutes tableRoutes = new TableRoutes(tableManager, deckResolver);
            tableRoutes.setOnJoined(social::joined);
            tableRoutes.setHostLinked(hostLinks::has);
            tableRoutes.setInvited(social::hasInvite);
            tableManager.setOnKicked(social::kicked);
            // Zuschauen: nur laufende Tisch-Spiele (WS /ws/game/{id}?spectate=1)
            httpServer.setSpectatePolicy(tableManager::runningTableName, uid -> tableManager.mine(uid).isPresent());
            httpServer.addModule(tableRoutes);
            // Lobby-Chat, Freunde, Einladungen
            httpServer.addModule(new SocialRoutes(social));
            // Admin-Bereich: Nutzer, Server-Uebersicht, Eingriffe
            httpServer.addModule(new AdminRoutes(new AdminService(db, accounts, games, tableManager, social, VERSION, remoteGames, hostLinks::count)));
            // Setup-Download fuer die Startseite (oeffentlich)
            httpServer.addModule(new DownloadRoutes(data.resolve("downloads")));
        } else {
            // Host-Link: diese Engine haengt sich an einen Server (Electron meldet das Session-Cookie)
            hostLink = new HostLinkClient(games);
            httpServer.addModule(new HostLinkRoutes(hostLink));
        }
        int port = httpServer.start();

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

        HostLinkClient hostLinkRef = hostLink;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (hostLinkRef != null) {
                hostLinkRef.disconnect();
            }
            games.shutdown();
            httpServer.stop();
            db.close();
        }, "shutdown"));

        if (!server) {
            Optional.ofNullable(opt.get("parent-pid")).map(Long::parseLong).ifPresent(Main::watchParent);
        }
        if (idleExitMin > 0) {
            watchIdle(httpServer, games, idleExitMin);
        }

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("port", port);
        ready.put("token", token);
        ready.put("bootMs", System.currentTimeMillis() - t0);
        System.out.println("MAGELITE_READY " + Json.write(ready));
        System.out.flush();
        log.info("MageLite-Engine bereit auf " + host + ":" + port + (server ? " (Server-Modus)" : "") + " in " + (System.currentTimeMillis() - t0) + " ms");
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

    /**
     * Leerlauf-Exit (fly Auto-Stop): kein laufendes Spiel und seit {@code minutes} keine API-Anfrage mehr -> Prozess
     * beenden. Offene WebSockets (vergessene Tabs) zaehlen bewusst nicht; sie werden mit 4404 geschlossen, die UI
     * verbindet sich nach dem naechsten Start neu.
     * <p>
     * Verwaiste Spiele (Tab geschlossen, Spiel wartet auf den Menschen) werden nach {@code minutes} ohne verbundenen
     * Client abgebrochen, sonst hielte das Spiel die Maschine ewig wach.
     */
    private static void watchIdle(HttpServer server, GameRegistry games, int minutes) {
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idle-watchdog");
            t.setDaemon(true);
            return t;
        });
        long limitMs = minutes * 60_000L;
        ses.scheduleAtFixedRate(() -> {
            for (GameHost g : games.runningGames()) {
                long gone = g.disconnectedForMs();
                if (gone > limitMs) {
                    Logger.getLogger(Main.class).warn("Spiel " + g.getId() + " seit " + (gone / 60_000) + " min ohne Spieler - wird abgebrochen");
                    g.abort();
                }
            }
            if (server.runningGames() > 0) {
                return; // auch Relay-Spiele (Host-Link) halten die Maschine wach
            }
            long idle = System.currentTimeMillis() - server.lastActivity();
            if (idle < limitMs) {
                return;
            }
            Logger.getLogger(Main.class).info("Leerlauf seit " + (idle / 60_000) + " min, kein Spiel - Engine stoppt (" + server.openSockets() + " offene Verbindungen)");
            server.closeAllSessions();
            System.exit(0);
        }, 1, 1, TimeUnit.MINUTES);
    }

    /** Anbindung der Tische an Host-Link und Relay-Spiele (Server-Modus). */
    private static TableManager.Remote remoteTables(HostLinks hostLinks, RemoteGames remoteGames) {
        return new TableManager.Remote() {
            @Override
            public boolean linked(long hostUserId) {
                return hostLinks.has(hostUserId);
            }

            @Override
            public java.util.UUID start(RemoteGameSpec spec) throws Exception {
                RemoteGameSpec started;
                try {
                    started = hostLinks.start(spec.hostUserId(), spec).get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new IllegalStateException("Keine Antwort vom Rechner des Gastgebers");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    throw new IllegalStateException(c.getMessage() == null ? c.toString() : c.getMessage());
                }
                return remoteGames.register(started).id;
            }

            @Override
            public boolean inGame(long userId) {
                return remoteGames.inGame(userId);
            }

            @Override
            public int turn(java.util.UUID gameId) {
                return remoteGames.turnOf(gameId);
            }

            @Override
            public void abort(java.util.UUID gameId) {
                remoteGames.abort(gameId);
            }
        };
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
