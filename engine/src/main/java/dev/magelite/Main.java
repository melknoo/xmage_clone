package dev.magelite;

import dev.magelite.admin.AdminRoutes;
import dev.magelite.admin.AdminService;
import dev.magelite.admin.UptimeBudget;
import dev.magelite.api.Auth;
import dev.magelite.api.DownloadRoutes;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import dev.magelite.api.TableRoutes;
import dev.magelite.deck.CardLookup;
import dev.magelite.deck.CardNameSuggester;
import dev.magelite.deck.DeckMigration;
import dev.magelite.deck.DeckResolver;
import dev.magelite.game.TableManager;
import dev.magelite.social.FriendStore;
import dev.magelite.social.SocialRoutes;
import dev.magelite.social.SocialService;
import dev.magelite.auth.AccountService;
import dev.magelite.auth.AuthRoutes;
import dev.magelite.auth.Mailer;
import dev.magelite.auth.SignupRoutes;
import dev.magelite.auth.SignupService;
import dev.magelite.auth.Turnstile;
import dev.magelite.boot.ForgeBoot;
import dev.magelite.boot.LogConfig;
import dev.magelite.deck.DeckRoutes;
import dev.magelite.deck.DeckStore;
import dev.magelite.deck.SampleDeckCatalog;
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
 * Args: {@code --port=0 --data=<dir> --forge=<dir> --ui=<dist> --parent-pid=<pid> --dev [--seed-db=<magelite.db>]}
 * Server-Modus: {@code --server --host=0.0.0.0 --max-games=1 --idle-exit-min=10 --anon-exit-min=3}; Owner-Konto aus
 * {@code MAGELITE_OWNER_CODE} / {@code MAGELITE_OWNER_NAME}; Monatsbudget {@code MAGELITE_BUDGET_HOURS} (Standard 100,
 * Test: {@code --budget-min}), Preis fuer die Anzeige {@code MAGELITE_PRICE_PER_HOUR}.
 * Das Arbeitsverzeichnis muss {@code <data>} sein (Forge-Profil mit relativen {@code forge-data/}-Pfaden).
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
        Path forge = Path.of(opt.getOrDefault("forge", System.getProperty("magelite.forge", "../../vendor/forge"))).toAbsolutePath().normalize();
        boolean dev = opt.containsKey("dev");
        boolean server = opt.containsKey("server");
        String host = opt.getOrDefault("host", "127.0.0.1");
        int maxGames = Integer.parseInt(opt.getOrDefault("max-games", "1"));
        int idleExitMin = Integer.parseInt(opt.getOrDefault("idle-exit-min", "0"));
        int anonExitMin = Integer.parseInt(opt.getOrDefault("anon-exit-min", String.valueOf(Math.min(3, idleExitMin))));
        Files.createDirectories(data.resolve("logs"));
        LogConfig.configure(data.resolve("logs"), dev || server); // Server: INFO auf stdout fuer `fly logs`
        Logger log = Logger.getLogger(Main.class);

        ForgeBoot.init(forge, data); // prueft auch Arbeitsverzeichnis == data

        if (opt.containsKey("seed-db")) {
            // Test-App (nicht gepackt): echte Daten der installierten App einmalig nur lesend uebernehmen
            try {
                Db.seedIfMissing(Path.of(opt.get("seed-db")), data.resolve("magelite.db"));
            } catch (Exception e) {
                log.warn("Test-Daten nicht uebernommen: " + e.getMessage());
            }
        }
        Db db = new Db(data.resolve("magelite.db"));
        DeckStore deckStore = new DeckStore(db);
        DeckMigration.run(db, data);
        SampleDeckCatalog samples = new SampleDeckCatalog();
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
        UptimeBudget budget = null;
        if (server) {
            long budgetMin = opt.containsKey("budget-min") ? Long.parseLong(opt.get("budget-min"))
                    : Math.round(envDouble("MAGELITE_BUDGET_HOURS", 100) * 60);
            budget = new UptimeBudget(db, budgetMin, envDouble("MAGELITE_PRICE_PER_HOUR", 0.0861));
            auth.setBudget(budget);
        }
        HttpServer httpServer = new HttpServer(cfg, auth, games, deckStore, samples);
        accounts.setOnRevoke(httpServer::closeSessionsOf);
        AuthRoutes authRoutes = new AuthRoutes(cfg, auth, accounts);
        httpServer.addModule(authRoutes);
        SignupService signup = server ? signupService(opt, data, dev, accounts, budget) : null;
        httpServer.addModule(new SignupRoutes(cfg, auth, accounts, signup));
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
            httpServer.addModule(new AdminRoutes(new AdminService(db, accounts, games, tableManager, social, VERSION, remoteGames, hostLinks::count, budget)));
            // Setup-Download fuer die Startseite (oeffentlich): nur der Link, die Datei liegt extern (GitHub-Releases)
            httpServer.addModule(new DownloadRoutes(System.getenv("MAGELITE_DOWNLOAD_URL"), VERSION));
        } else {
            // Host-Link: diese Engine haengt sich an einen Server (Electron meldet das Session-Cookie)
            hostLink = new HostLinkClient(games);
            httpServer.addModule(new HostLinkRoutes(hostLink));
        }
        int port = httpServer.start();

        // Sample-Katalog und Kartenindizes (Set-Codes, Namen fuer "Meintest du ...?") im Hintergrund vorbereiten
        Thread warmup = new Thread(() -> {
            try {
                samples.list();
                CardLookup.warmup();
                CardNameSuggester.warmup();
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
        if (idleExitMin > 0 || budget != null) {
            watchIdle(httpServer, games, idleExitMin, anonExitMin, budget);
        }

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("port", port);
        ready.put("token", token);
        ready.put("bootMs", System.currentTimeMillis() - t0);
        System.out.println("MAGELITE_READY " + Json.write(ready));
        System.out.flush();
        log.info("MageLite-Engine bereit auf " + host + ":" + port + (server ? " (Server-Modus)" : "") + " in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /**
     * Selbstregistrierung (Server-Modus). Offen nur mit {@code MAGELITE_SIGNUP=open} und - ausser im Dev-Modus - mit
     * Mail-Key, Captcha-Secret und {@code MAGELITE_PUBLIC_URL}; sonst bleibt sie geschlossen (Fehler im Log). Im Dev-Modus
     * ohne Mail-Key landen Mails als JSON in {@code <data>/mail-outbox} (e2e liest die Links daraus).
     */
    private static SignupService signupService(Map<String, String> opt, Path data, boolean dev, AccountService accounts, UptimeBudget budget) {
        Logger log = Logger.getLogger(Main.class);
        boolean open = "open".equalsIgnoreCase(env("MAGELITE_SIGNUP", "closed"));
        String mailKey = env("MAGELITE_MAIL_API_KEY", null);
        String mailFrom = env("MAGELITE_MAIL_FROM", null);
        Mailer mailer = null;
        if (mailKey != null && mailFrom != null) {
            mailer = new Mailer.Brevo(mailKey, mailFrom, env("MAGELITE_MAIL_FROM_NAME", "MageLite"));
        } else if (dev) {
            mailer = new Mailer.Outbox(data.resolve("mail-outbox"));
        }
        Turnstile turnstile = new Turnstile(env("MAGELITE_TURNSTILE_SECRET", null));
        String publicUrl = env("MAGELITE_PUBLIC_URL", dev ? "http://localhost:5173" : null);
        if (publicUrl != null && publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }
        boolean captchaOk = turnstile.enabled() && env("MAGELITE_TURNSTILE_SITEKEY", null) != null;
        if (open && !dev && (mailer == null || !captchaOk || publicUrl == null)) {
            log.error("MAGELITE_SIGNUP=open, aber Mail (MAGELITE_MAIL_API_KEY/_FROM), Captcha (MAGELITE_TURNSTILE_SECRET/_SITEKEY) oder "
                    + "MAGELITE_PUBLIC_URL fehlen - Registrierung bleibt geschlossen");
            open = false;
        }
        long ttlMin = Long.parseLong(opt.getOrDefault("unverified-ttl-min", "1440"));
        SignupService.Settings settings = new SignupService.Settings(open,
                (int) envDouble("MAGELITE_MAX_PUBLIC_USERS", 200), (int) envDouble("MAGELITE_SIGNUPS_PER_DAY", 30),
                (int) envDouble("MAGELITE_SIGNUPS_PER_IP", 3), publicUrl,
                turnstile.enabled() ? env("MAGELITE_TURNSTILE_SITEKEY", null) : null, ttlMin * 60_000L);
        SignupService signup = new SignupService(accounts, mailer, turnstile, settings, budget);
        log.info("Registrierung: " + signup.state().name().toLowerCase(java.util.Locale.ROOT)
                + (mailer == null ? " (kein Mailversand)" : "") + (turnstile.enabled() ? "" : " (ohne Captcha)"));
        // Owner bei 80 % / 100 % des Monatsbudgets per Mail warnen (wenn er eine E-Mail hinterlegt hat)
        Mailer warnMailer = mailer;
        if (budget != null && warnMailer != null) {
            budget.setOnThreshold((pct, st) -> accounts.ownerEmail().ifPresent(to -> warnMailer.send(to,
                    "MageLite: " + pct + " % des Server-Budgets verbraucht",
                    "Laufzeit im " + st.month() + ": " + (st.minutes() / 60) + " von " + (st.budgetMin() / 60) + " Stunden"
                            + String.format(java.util.Locale.ROOT, " (ca. %.2f $).", st.minutes() / 60.0 * st.pricePerHour())
                            + (pct >= 100 ? "\n\nÖffentliche Konten sind bis Monatsende gesperrt; eingeladene Spieler spielen weiter.\n"
                            : "\n"))));
        }
        // unbestaetigte Konten: beim Start und stuendlich aufraeumen
        ScheduledExecutorService purge = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "signup-purge");
            t.setDaemon(true);
            return t;
        });
        purge.scheduleAtFixedRate(signup::purge, 0, 60, TimeUnit.MINUTES);
        return signup;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.strip();
    }

    private static double envDouble(String name, double def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.strip());
        } catch (NumberFormatException e) {
            Logger.getLogger(Main.class).warn(name + " ungueltig (" + v + "), nehme " + def);
            return def;
        }
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
     * Leerlauf-Exit (fly Auto-Stop): kein laufendes Spiel und seit {@code minutes} keine API-Anfrage eines angemeldeten
     * Nutzers mehr -> Prozess beenden. Offene WebSockets (vergessene Tabs) zaehlen bewusst nicht; sie werden mit 4404
     * geschlossen, die UI verbindet sich nach dem naechsten Start neu. Hat seit dem Start nur Anonymes die Maschine
     * geweckt (Startseite, Crawler), endet sie schon nach {@code anonMinutes}.
     * <p>
     * Verwaiste Spiele (Tab geschlossen, Spiel wartet auf den Menschen) werden nach {@code minutes} ohne verbundenen
     * Client abgebrochen, sonst hielte das Spiel die Maschine ewig wach. Jede Minute Laufzeit geht ins Monatsbudget.
     */
    private static void watchIdle(HttpServer server, GameRegistry games, int minutes, int anonMinutes, UptimeBudget budget) {
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idle-watchdog");
            t.setDaemon(true);
            return t;
        });
        long limitMs = minutes * 60_000L;
        long anonLimitMs = Math.max(1, anonMinutes) * 60_000L;
        ses.scheduleAtFixedRate(() -> {
            if (budget != null) {
                try {
                    budget.tick();
                } catch (RuntimeException e) {
                    Logger.getLogger(Main.class).warn("Budget-Zaehler: " + e);
                }
            }
            for (GameHost g : minutes > 0 ? games.runningGames() : java.util.List.<GameHost>of()) {
                long gone = g.disconnectedForMs();
                if (gone > limitMs) {
                    Logger.getLogger(Main.class).warn("Spiel " + g.getId() + " seit " + (gone / 60_000) + " min ohne Spieler - wird abgebrochen");
                    g.abort();
                }
            }
            if (minutes <= 0 || server.runningGames() > 0) {
                return; // kein Leerlauf-Exit konfiguriert; auch Relay-Spiele (Host-Link) halten die Maschine wach
            }
            long last = server.lastActivity();
            boolean anonOnly = last == 0;
            long idle = System.currentTimeMillis() - (anonOnly ? server.startedAt() : last);
            if (idle < (anonOnly ? anonLimitMs : limitMs)) {
                return;
            }
            Logger.getLogger(Main.class).info((anonOnly ? "Seit dem Start keine angemeldete Anfrage" : "Leerlauf seit " + (idle / 60_000) + " min")
                    + ", kein Spiel - Engine stoppt (" + server.openSockets() + " offene Verbindungen)");
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
