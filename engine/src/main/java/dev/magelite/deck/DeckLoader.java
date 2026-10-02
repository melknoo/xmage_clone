package dev.magelite.deck;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.deck.Commander;
import mage.game.GameException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Laedt XMage-Decks (.dck etc.) ueber die XMage-Importer und validiert sie als Commander-Deck.
 */
public final class DeckLoader {

    private DeckLoader() {
    }

    public static LoadedDeck loadFile(Path file) throws GameException {
        StringBuilder errors = new StringBuilder();
        DeckCardLists lists = DeckImporter.importDeckFromFile(file.toString(), errors, false);
        String name = lists.getName() != null && !lists.getName().isBlank()
                ? lists.getName()
                : stripExtension(file.getFileName().toString());
        lists.setName(name);
        return fromLists(lists, file.toString(), errors.toString());
    }

    /**
     * Laedt ein Deck aus .dck-Text (gespeicherte eigene Decks).
     */
    public static LoadedDeck fromDckText(String dck, String name, String source) throws GameException, IOException {
        Path tmp = Files.createTempFile("magelite-", ".dck");
        try {
            Files.writeString(tmp, dck, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder errors = new StringBuilder();
            DeckCardLists lists = DeckImporter.importDeckFromFile(tmp.toString(), errors, false);
            lists.setName(name);
            return fromLists(lists, source, errors.toString());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static LoadedDeck fromLists(DeckCardLists lists, String source, String importErrors) throws GameException {
        Deck deck = Deck.load(lists, true, false);
        Commander validator = new Commander();
        boolean valid = validator.validate(deck);
        String validation = valid ? "" : validator.getErrorsListInfo();
        return new LoadedDeck(lists.getName(), source, lists, LoadedDeck.commanderNames(deck),
                deck.getMaindeckCards().size(), valid, validation, importErrors);
    }

    /**
     * Alle Deck-Dateien unterhalb von {@code dir} (rekursiv).
     */
    public static List<Path> listDeckFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".dck") || n.endsWith(".txt") || n.endsWith(".dec");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static String stripExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(0, i) : fileName;
    }
}
