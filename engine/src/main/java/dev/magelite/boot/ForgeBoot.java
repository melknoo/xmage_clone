package dev.magelite.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.apache.log4j.Logger;
import org.tinylog.configuration.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Startet die Forge-Regel-Engine einmal pro JVM (ersetzt die XMage-Karten-DB).
 * <p>
 * {@code forgeHome} ist das von {@code scripts/import-forge.ps1} erzeugte Verzeichnis ({@code vendor/forge}: {@code res/},
 * {@code forge.profile.properties}, {@code manifest.json}). Das Forge-Profil enthaelt relative Pfade
 * ({@code forge-data/...}), deshalb muss das Arbeitsverzeichnis der Datenordner sein. Kartenskripte werden eager
 * geladen (vollstaendige Namenslisten, keine Nachlade-Races unter mehreren Spielen); es gibt keine persistente
 * Karten-DB mehr, der Boot kostet bei jedem Start einige Sekunden.
 */
public final class ForgeBoot {

    private static final Logger LOG = Logger.getLogger(ForgeBoot.class);
    /** KI-Profil der Bots ({@link #registerAiProfile}) */
    public static final String AI_PROFILE = "MageLite";

    /** Kennzahlen des Boots (fuer Log, {@code forgeCheck} und spaeter /api/health). */
    public record Info(String forgeVersion, String commit, int cards, int editions, int tokens, long ms, long heapMb) {
    }

    private static Info info;

    private ForgeBoot() {
    }

    /** Liefert die Boot-Kennzahlen oder null, solange Forge nicht gestartet ist. */
    public static synchronized Info info() {
        return info;
    }

    public static synchronized Info init(Path forgeHome, Path data) throws IOException {
        if (info != null) {
            return info;
        }
        long t0 = System.currentTimeMillis();
        forgeHome = forgeHome.toAbsolutePath().normalize();
        data = data.toAbsolutePath().normalize();

        LegacyCleanup.run(data);

        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (!cwd.equals(data)) {
            LOG.error("Arbeitsverzeichnis (" + cwd + ") != Datenordner (" + data + ") - Forge legt forge-data/ unter "
                    + cwd + " an");
        }
        JsonNode manifest = readManifest(forgeHome);
        String commit = manifest.path("commit").asText("");
        String forgeVersion = manifest.path("forgeVersion").asText("?");
        checkCommit(commit);
        if (!Files.isRegularFile(forgeHome.resolve("res/cardsfolder/cardsfolder.zip"))) {
            throw new IllegalStateException("Forge-Kartenskripte fehlen (" + forgeHome.resolve("res/cardsfolder")
                    + ") - scripts\\import-forge.ps1 ausfuehren");
        }

        configureTinylog(data.resolve("logs").resolve("forge.log"));

        GuiBase.setInterface(new HeadlessGui(forgeHome.toFile(), forgeVersion));
        // Forges ExceptionHandler setzt beim Laden einen globalen Default-Handler (Bug-Report-Dialog); wir behalten unseren
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            prefs.setPref(FPref.DECKGEN_CARDBASED, false);
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            prefs.setPref(FPref.UI_LOAD_UNKNOWN_CARDS, false);
            prefs.setPref(FPref.UI_LOAD_NONLEGAL_CARDS, true);
            prefs.setPref(FPref.UI_PREFERRED_ART, "LATEST_ART_CORE_EXPANSIONS_REPRINT_ONLY");
            prefs.setPref(FPref.UI_SMART_CARD_ART, false);
            prefs.setPref(FPref.LOAD_ARCHIVED_FORMATS, false);
            prefs.setPref(FPref.PLAYER_NAME, "MageLite");
            prefs.setPref(FPref.MAX_LOG_FILES, "3");
            prefs.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, false);
            prefs.setPref(FPref.UI_ENABLE_SOUNDS, false);
            prefs.setPref(FPref.UI_ENABLE_MUSIC, false);
            prefs.setPref(FPref.USE_SENTRY, false);
            prefs.setPref(FPref.AUTO_UPDATE, "none");
            // Passen entscheidet MageLite je Sitz (AutoPassPolicy), nie Forges globale Pref
            prefs.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, false);
            // sonst fragt Forge bei aufgedeckten Gegner-Haenden "OK / Zug beenden" und nutzt eigene Kartenlisten-Dialoge
            prefs.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);
            return null;
        });
        Thread.setDefaultUncaughtExceptionHandler(previous);
        registerAiProfile();

        StaticData db = FModel.getMagicDb();
        int cards = db.getCommonCards().getUniqueCards().size();
        int editions = db.getEditions().size();
        int tokens = db.getAllTokens().getRules().size();
        if (cards < 25000 || editions < 500 || db.getCommanderPredicate() == null) {
            throw new IllegalStateException("Forge-Daten unvollstaendig: " + cards + " Karten, " + editions
                    + " Editionen, Commander-Praedikat " + (db.getCommanderPredicate() != null)
                    + " - scripts\\import-forge.ps1 erneut ausfuehren");
        }
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long ms = System.currentTimeMillis() - t0;
        info = new Info(forgeVersion, commit, cards, editions, tokens, ms, heapMb);
        LOG.info("Forge " + forgeVersion + " (" + shortSha(commit) + "): " + cards + " Karten, " + editions
                + " Editionen, " + tokens + " Token, " + ms + " ms, Heap " + heapMb + " MB");
        return info;
    }

    /**
     * KI-Profil der Bots: Forges "Default" ohne zufaellige Blocker-Trades. Deren Bewertung zaehlt fuer jedes
     * Angreifer-Blocker-Paar alle Kreaturen neu und braucht bei Token-Boards Minuten (Forge-POC, Befund F27).
     * Forge kennt keine Profile aus dem Code, daher per Reflection in die geladene Profiltabelle.
     */
    @SuppressWarnings("unchecked")
    private static void registerAiProfile() {
        try {
            Field f = AiProfileUtil.class.getDeclaredField("loadedProfiles");
            f.setAccessible(true);
            Map<String, Map<AiProps, String>> profiles = (Map<String, Map<AiProps, String>>) f.get(null);
            Map<AiProps, String> base = profiles.get("Default");
            Map<AiProps, String> mine = base == null ? new HashMap<>() : new HashMap<>(base);
            mine.put(AiProps.ENABLE_RANDOM_FAVORABLE_TRADES_ON_BLOCK, "false");
            profiles.put(AI_PROFILE, mine);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("KI-Profil " + AI_PROFILE + " nicht angelegt, Bots nutzen Forge-Standardwerte: " + e);
        }
    }

    private static JsonNode readManifest(Path forgeHome) throws IOException {
        Path file = forgeHome.resolve("manifest.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Forge fehlt: " + file + " nicht gefunden - scripts\\import-forge.ps1 ausfuehren");
        }
        return new ObjectMapper().readTree(file.toFile());
    }

    /** Jars und res muessen vom Commit stammen, gegen den die Engine gebaut wurde ({@code vendor/forge/FORGE_COMMIT}). */
    private static void checkCommit(String manifestCommit) throws IOException {
        String built = builtForgeCommit();
        if (built == null || built.isBlank()) {
            LOG.warn("Engine ohne eingebauten Forge-Commit gestartet - Abgleich mit manifest.json uebersprungen");
            return;
        }
        if (!built.equalsIgnoreCase(manifestCommit)) {
            throw new IllegalStateException("Forge-Daten (" + shortSha(manifestCommit) + ") passen nicht zur Engine ("
                    + shortSha(built) + ") - Engine neu bauen oder scripts\\import-forge.ps1 ausfuehren");
        }
    }

    private static String builtForgeCommit() throws IOException {
        try (InputStream in = ForgeBoot.class.getResourceAsStream("/magelite-version.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("forgeCommit");
        }
    }

    /** Muss vor der ersten Forge-Klasse laufen, die loggt (danach ist tinylog eingefroren). */
    private static void configureTinylog(Path logFile) throws IOException {
        Files.createDirectories(logFile.getParent());
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("writer", "file");
        cfg.put("writer.file", logFile.toString().replace('\\', '/'));
        cfg.put("writer.level", "warn");
        cfg.put("writer.charset", "UTF-8");
        cfg.put("writer.append", "false");
        cfg.put("writer.format", "{date: yyyy-MM-dd HH:mm:ss} {level} [{thread}] {class-name}: {message}");
        try {
            Configuration.replace(cfg);
        } catch (UnsupportedOperationException e) {
            LOG.warn("tinylog bereits aktiv - Forge loggt mit Standardkonfiguration (Konsole)");
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(12, sha.length()));
    }

    /**
     * {@code gradlew forgeCheck}: Forge booten und Kennzahlen ausgeben. Args {@code --forge=<dir> --data=<dir>}
     * (Default {@code -Dmagelite.forge} bzw. {@code ../../vendor/forge}, Datenordner = Arbeitsverzeichnis).
     */
    public static void main(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) {
                opt.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            }
        }
        Path forge = Path.of(opt.getOrDefault("forge", System.getProperty("magelite.forge", "../../vendor/forge")));
        Path data = Path.of(opt.getOrDefault("data", "."));
        try {
            Files.createDirectories(data.resolve("logs"));
            LogConfig.configure(data.toAbsolutePath().resolve("logs"), true);
            Info i = init(forge, data);
            System.gc();
            Runtime rt = Runtime.getRuntime();
            long retainedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            System.out.println("FORGE_CHECK ok version=" + i.forgeVersion() + " commit=" + shortSha(i.commit())
                    + " cards=" + i.cards() + " editions=" + i.editions() + " tokens=" + i.tokens() + " bootMs=" + i.ms()
                    + " heapMb=" + i.heapMb() + " heapAfterGcMb=" + retainedMb);
            System.exit(0);
        } catch (Throwable t) {
            LOG.error("Forge-Boot fehlgeschlagen", t);
            System.out.println("FORGE_CHECK failed: " + t);
            System.exit(1);
        }
    }
}
