package dev.magelite.spike;

import dev.magelite.deck.LoadedDeck;
import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Forge-POC: liest die XMage-Sample-Decks ({@code 1 [SET:num] Name}, Commander als {@code SB:}) nur ueber den
 * Kartennamen in Forge-Decks. Ersetzt in Phase 2 durch den Deck-Layer (CardLookup, Decktext v2).
 */
final class PocDecks {

    private static final Pattern LINE = Pattern.compile("^(SB:\\s*)?(\\d+)\\s+(?:\\[[^\\]]*\\]\\s+)?(.+)$");

    private PocDecks() {
    }

    static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".dck")).sorted().toList();
        }
    }

    static LoadedDeck load(Path file) throws IOException {
        String name = file.getFileName().toString().replaceFirst("\\.dck$", "");
        Deck deck = new Deck(name);
        CardPool main = deck.getMain();
        CardPool cmd = deck.getOrCreate(DeckSection.Commander);
        List<String> unknown = new ArrayList<>();
        int mainCount = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("NAME:") || line.startsWith("LAYOUT")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int n = Integer.parseInt(m.group(2));
            PaperCard pc = lookup(m.group(3).trim());
            if (pc == null) {
                unknown.add(m.group(3).trim());
                continue;
            }
            if (m.group(1) != null) {
                cmd.add(pc, n);
            } else {
                main.add(pc, n);
                mainCount += n;
            }
        }
        String problem = DeckFormat.Commander.getDeckConformanceProblem(deck);
        boolean valid = problem == null && unknown.isEmpty();
        String validation = problem == null ? "" : problem;
        String imports = unknown.isEmpty() ? "" : "Unbekannte Karten: " + String.join(", ", unknown);
        return new LoadedDeck(name, "sample:" + file.getFileName(), deck, LoadedDeck.commanderNames(deck), mainCount, valid,
                validation, imports, null);
    }

    private static PaperCard lookup(String name) {
        var db = StaticData.instance().getCommonCards();
        PaperCard pc = db.getCard(name);
        if (pc == null && name.contains(" // ")) {
            pc = db.getCard(name.substring(0, name.indexOf(" // ")));
        }
        if (pc == null && name.contains("/") && !name.contains(" // ")) {
            pc = db.getCard(name.replace("/", " // "));
        }
        return pc;
    }
}
