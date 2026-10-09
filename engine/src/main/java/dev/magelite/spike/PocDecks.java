package dev.magelite.spike;

import dev.magelite.deck.DeckLoader;
import dev.magelite.deck.LoadedDeck;
import dev.magelite.deck.SampleDeckCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Decks fuer Spikes und Arena: die Sample-Decks aus dem Classpath oder ein eigenes Verzeichnis mit Decktexten
 * ({@code .dck}/{@code .txt}, MageLite-Text v2 oder XMage-.dck). Geladen wird ueber den normalen Deck-Layer.
 */
final class PocDecks {

    /** {@code file} null = Sample-Deck aus dem Classpath ({@code id} = Katalog-id). */
    record Ref(String id, String fileName, Path file) {
    }

    private static SampleDeckCatalog catalog;

    private PocDecks() {
    }

    /** @param dir eigenes Verzeichnis oder null fuer die Sample-Decks */
    static List<Ref> list(Path dir) throws IOException {
        if (dir == null) {
            return catalog().list().stream()
                    .map(e -> new Ref(e.id(), e.id().substring(e.id().lastIndexOf('/') + 1), null))
                    .sorted(Comparator.comparing(Ref::id)).toList();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".dck") || p.toString().endsWith(".txt")).sorted()
                    .map(p -> new Ref(dir.relativize(p).toString().replace('\\', '/'), p.getFileName().toString(), p)).toList();
        }
    }

    static LoadedDeck load(Ref r) throws Exception {
        String name = r.fileName().replaceFirst("\\.(dck|txt)$", "");
        String text = r.file() == null ? catalog().text(r.id()) : Files.readString(r.file(), StandardCharsets.UTF_8);
        return DeckLoader.fromText(text, name, "sample:" + r.id());
    }

    private static synchronized SampleDeckCatalog catalog() {
        if (catalog == null) {
            catalog = new SampleDeckCatalog();
        }
        return catalog;
    }
}
