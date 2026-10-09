package dev.magelite.deck;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Loest eine Deck-Angabe aus der API auf: {@code {type:"user",id}} (eigene Bibliothek des Nutzers),
 * {@code {type:"sample",id}} (mitgeliefert) oder {@code {type:"random"}} (zufaelliges mitgeliefertes Deck,
 * ohne Wiederholung innerhalb eines Spiels).
 */
public final class DeckResolver {

    private final DeckStore deckStore;
    private final SampleDeckCatalog samples;
    private final Random random = new Random();

    public DeckResolver(DeckStore deckStore, SampleDeckCatalog samples) {
        this.deckStore = deckStore;
        this.samples = samples;
    }

    /** Deck-id, falls es ein gespeichertes Deck des Nutzers ist (fuer Meisterschaft/Statistik). */
    public static Long userDeckId(JsonNode spec) {
        return spec != null && "user".equals(spec.path("type").asText()) ? spec.path("id").asLong() : null;
    }

    /**
     * @param usedSamples schon vergebene Sample-Decks dieses Spiels (null = nicht mitzaehlen)
     */
    public LoadedDeck resolve(long userId, JsonNode spec, List<String> usedSamples) throws Exception {
        String type = spec == null ? "random" : spec.path("type").asText("random");
        switch (type) {
            case "user" -> {
                long id = spec.path("id").asLong();
                DeckStore.StoredDeck d = deckStore.get(userId, id).orElseThrow(() -> new IllegalArgumentException("Deck " + id + " nicht gefunden"));
                String text = deckStore.getDck(userId, id).orElseThrow();
                return DeckLoader.fromText(text, d.name(), "user:" + id);
            }
            case "sample" -> {
                String id = spec.path("id").asText();
                SampleDeckCatalog.Entry entry = samples.find(id).orElseThrow(() -> new IllegalArgumentException("Sample-Deck nicht gefunden: " + id));
                if (usedSamples != null) {
                    usedSamples.add(id);
                }
                return DeckLoader.fromText(samples.text(id), entry.name(), "sample:" + id);
            }
            default -> {
                List<SampleDeckCatalog.Entry> pool = new ArrayList<>(samples.list());
                if (usedSamples != null) {
                    pool.removeIf(e -> usedSamples.contains(e.id()));
                }
                while (!pool.isEmpty()) {
                    SampleDeckCatalog.Entry e = pool.remove(random.nextInt(pool.size()));
                    LoadedDeck d = DeckLoader.fromText(samples.text(e.id()), e.name(), "sample:" + e.id());
                    if (d.valid() || pool.isEmpty()) {
                        if (usedSamples != null) {
                            usedSamples.add(e.id());
                        }
                        return d;
                    }
                }
                throw new IllegalStateException("Keine Sample-Decks gefunden");
            }
        }
    }

    /**
     * Anzeige-Infos einer Deck-Angabe fuer die Lobby (ohne das Deck zu laden). {@code commander} = Namen mit " & ",
     * {@code deckName} = bisheriges Format "Name (Commander)".
     */
    public record DeckInfo(String title, String commander, String colors, String commanderSet, String commanderNum, String deckName) {
    }

    public Optional<DeckInfo> info(long userId, JsonNode spec) {
        if (spec == null) {
            return Optional.empty();
        }
        switch (spec.path("type").asText("random")) {
            case "user" -> {
                return deckStore.get(userId, spec.path("id").asLong()).map(d -> {
                    String cmd = d.commanders().isEmpty() ? null : String.join(" & ", d.commanders());
                    return new DeckInfo(d.name(), cmd, emptyToNull(d.colors()), d.commanderSet(), d.commanderNum(),
                            d.name() + (cmd == null ? "" : " (" + cmd + ")"));
                });
            }
            case "sample" -> {
                return samples.find(spec.path("id").asText()).map(e -> new DeckInfo(e.name(),
                        e.commanders().isEmpty() ? null : String.join(" & ", e.commanders()), emptyToNull(e.colors()),
                        e.commanderSet(), e.commanderNum(), e.name()));
            }
            default -> {
                return Optional.of(new DeckInfo("Zufälliges Deck", null, null, null, null, "Zufälliges Deck"));
            }
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Anzeigename einer Deck-Angabe ohne das Deck zu laden (fuer Lobby/Tisch). */
    public Optional<String> describe(long userId, JsonNode spec) {
        if (spec == null) {
            return Optional.empty();
        }
        switch (spec.path("type").asText("random")) {
            case "user" -> {
                return deckStore.get(userId, spec.path("id").asLong()).map(d -> d.name() + (d.commanders().isEmpty() ? "" : " (" + String.join(" & ", d.commanders()) + ")"));
            }
            case "sample" -> {
                return samples.find(spec.path("id").asText()).map(SampleDeckCatalog.Entry::name);
            }
            default -> {
                return Optional.of("Zufälliges Deck");
            }
        }
    }
}
