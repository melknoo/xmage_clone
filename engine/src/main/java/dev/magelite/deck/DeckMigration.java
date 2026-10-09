package dev.magelite.deck;

import dev.magelite.stats.Db;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Einmalige Umstellung gespeicherter Decks vom XMage-.dck ({@code deck_format=1}) auf MageLite-Text v2 (Forge,
 * {@code deck_format=2}). Laeuft synchron beim Start nach {@code ForgeBoot} und {@code Db}, vor dem HTTP-Server.
 * Vorher einmal {@code VACUUM INTO magelite.db.xmage-backup} (einziger Weg zurueck). Der alte Text bleibt in
 * {@code dck_legacy}; {@code updated_at}, Meisterschaft, Ordner und Sortierung bleiben unangetastet. Idempotent ueber
 * {@code deck_format}: fehlgeschlagene Decks bleiben Format 1 und werden beim naechsten Start erneut versucht.
 */
public final class DeckMigration {

    private static final Logger LOG = Logger.getLogger(DeckMigration.class);
    public static final String BACKUP = "magelite.db.xmage-backup";

    /** Ergebnis einer Umstellung (fuer Tests und Log). */
    public record Summary(int decks, List<Long> withUnknown, List<Long> failed) {
    }

    /** Neue Spaltenwerte eines umgestellten Decks. */
    record Converted(String text, List<String> commanders, String colors, String commanderSet, String commanderNum,
                     int cardCount, boolean valid, String validation, BracketAnalyzer.Result bracket, boolean unknown) {
    }

    private DeckMigration() {
    }

    public static Summary run(Db db, Path data) {
        DeckStore store = new DeckStore(db);
        List<DeckStore.LegacyDeck> todo = store.legacyDecks();
        if (todo.isEmpty()) {
            return new Summary(0, List.of(), List.of());
        }
        backup(db, data.resolve(BACKUP));
        List<Long> withUnknown = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        int done = 0;
        for (DeckStore.LegacyDeck d : todo) {
            try {
                Converted c = convert(d);
                store.migrate(d.id(), c);
                done++;
                if (c.unknown()) {
                    withUnknown.add(d.id());
                }
            } catch (RuntimeException e) {
                failed.add(d.id());
                LOG.warn("Deck-Umstellung: Deck " + d.id() + " fehlgeschlagen: " + e, e);
            }
        }
        LOG.info("Deck-Umstellung: " + done + " Decks, " + withUnknown.size() + " mit unbekannten Karten"
                + (withUnknown.isEmpty() ? "" : " (ids " + withUnknown + ")") + ", " + failed.size() + " Fehler"
                + (failed.isEmpty() ? "" : " (ids " + failed + ")"));
        return new Summary(done, withUnknown, failed);
    }

    /** WAL-konsistente Kopie der ganzen DB, nur wenn es noch keine gibt (sonst bliebe nur der erste Altstand erhalten). */
    private static void backup(Db db, Path file) {
        if (Files.exists(file)) {
            return;
        }
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + file.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
        LOG.info("Deck-Umstellung: Sicherung " + file.getFileName() + " angelegt");
    }

    static Converted convert(DeckStore.LegacyDeck d) {
        TextDeckParser.Result r = TextDeckParser.parse(d.dck(), d.name(), d.commanders());
        if (r.commanders().size() < d.commanders().size()) {
            // Forge kennt einen gespeicherten Commander-Namen nicht: Commander aus dem Text bestimmen (SB-Zeilen)
            TextDeckParser.Result guess = TextDeckParser.parse(d.dck(), d.name(), null);
            if (guess.commanders().size() > r.commanders().size()) {
                r = guess;
            }
        }
        if (r.commanders().isEmpty()) {
            throw new IllegalStateException("kein Commander erkennbar");
        }
        List<String> names = r.commanders().stream().map(TextDeckParser.Resolved::name).toList();
        TextDeckParser.Resolved first = r.commanders().get(0);
        String text = r.toText();
        LoadedDeck loaded = DeckLoader.fromText(text, r.name(), "migration");
        boolean unknown = !r.unknown().isEmpty();
        boolean valid = loaded.valid() && !unknown;
        String validation = loaded.validationErrors();
        if (unknown) {
            String msg = "Nach dem Wechsel auf Forge unbekannt: " + String.join(", ", r.unknown());
            validation = validation == null || validation.isBlank() ? msg : msg + "\n" + validation;
        }
        return new Converted(text, names, SampleDeckCatalog.colorsOf(names), first.set(), first.number(), r.cardCount(),
                valid, validation, DeckRoutes.bracketOf(r), unknown);
    }
}
