package dev.magelite;

import dev.magelite.boot.ForgeBoot;
import dev.magelite.boot.LogConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Bootet Forge fuer Tests einmal pro Test-JVM ({@link #boot()} ist statisch, synchronisiert und idempotent; jede Testklasse
 * ruft es im {@code @BeforeAll}). Gradle startet die Tests im Arbeitsverzeichnis {@code engine/run} mit
 * {@code -Dmagelite.forge=<vendor/forge>}; dort entsteht {@code forge-data/} und {@code logs/}.
 * <p>
 * Beim ersten Boot wird festgehalten, ob Forge ausserhalb des Datenordners etwas anlegt oder veraendert
 * ({@link #outsideWrites()}): Vendor-Verzeichnis (Dateien, Groessen, Zeitstempel) und typische Ablageorte relativ zum Repo.
 */
public final class ForgeTestSupport {

    private static ForgeBoot.Info info;
    private static List<String> outside = List.of();

    private ForgeTestSupport() {
    }

    public static Path forgeHome() {
        return Path.of(System.getProperty("magelite.forge", "../../vendor/forge")).toAbsolutePath().normalize();
    }

    /** Datenordner = Arbeitsverzeichnis der Test-JVM. */
    public static Path dataDir() {
        return Path.of("").toAbsolutePath().normalize();
    }

    public static synchronized ForgeBoot.Info boot() {
        if (info != null) {
            return info;
        }
        try {
            Path data = dataDir();
            Files.createDirectories(data.resolve("logs"));
            LogConfig.configure(data.resolve("logs"), false);
            Path repo = repoRoot(data);
            Map<String, String> vendorBefore = snapshot(forgeHome());
            List<Path> candidates = candidates(repo, data);
            List<Boolean> existedBefore = new ArrayList<>();
            for (Path c : candidates) {
                existedBefore.add(Files.exists(c));
            }

            info = ForgeBoot.init(forgeHome(), data);

            List<String> violations = new ArrayList<>();
            Map<String, String> vendorAfter = snapshot(forgeHome());
            if (!vendorBefore.equals(vendorAfter)) {
                for (var e : vendorAfter.entrySet()) {
                    if (!e.getValue().equals(vendorBefore.get(e.getKey()))) {
                        violations.add("vendor/forge geaendert: " + e.getKey());
                    }
                }
                for (String k : vendorBefore.keySet()) {
                    if (!vendorAfter.containsKey(k)) {
                        violations.add("vendor/forge geloescht: " + k);
                    }
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                if (!existedBefore.get(i) && Files.exists(candidates.get(i))) {
                    violations.add("neu angelegt: " + candidates.get(i));
                }
            }
            outside = List.copyOf(violations);
            return info;
        } catch (IOException e) {
            throw new IllegalStateException("Forge-Boot im Test fehlgeschlagen: " + e, e);
        }
    }

    /** Dateien, die Forge beim ersten Boot ausserhalb des Datenordners angelegt oder geaendert hat (leer = sauber). */
    public static synchronized List<String> outsideWrites() {
        return outside;
    }

    /** Repo-Wurzel, wenn die Tests in {@code <repo>/engine/run} laufen, sonst null. */
    private static Path repoRoot(Path data) {
        Path engine = data.getParent();
        if (data.getFileName() != null && data.getFileName().toString().equals("run") && engine != null
                && engine.getFileName() != null && engine.getFileName().toString().equals("engine")) {
            return engine.getParent();
        }
        return null;
    }

    /** Orte, an denen ein falsch aufgeloester Forge-Pfad landen wuerde (relativ zu Repo, engine, vendor). */
    private static List<Path> candidates(Path repo, Path data) {
        List<Path> out = new ArrayList<>();
        Path forge = forgeHome();
        out.add(forge.resolve("forge-data"));
        out.add(forge.resolve("logs"));
        out.add(forge.getParent().resolve("forge-data"));
        if (repo != null) {
            out.add(repo.resolve("forge-data"));
            out.add(repo.resolve("logs"));
            out.add(repo.resolve("engine").resolve("forge-data"));
            out.add(repo.resolve("engine").resolve("logs"));
        }
        // Parent des Datenordners, falls die Tests anderswo laufen (Entwicklung in der IDE)
        if (data.getParent() != null && !data.getParent().equals(data)) {
            out.add(data.getParent().resolve("forge-data"));
        }
        return out;
    }

    /** relative Datei -&gt; "Groesse:mtime" (alles unter {@code dir}). */
    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> m = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return m;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    m.put(dir.relativize(p).toString().replace('\\', '/'), Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis());
                }
            }
        }
        return m;
    }
}
