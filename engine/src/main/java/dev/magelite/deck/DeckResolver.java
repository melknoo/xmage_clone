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
                String dck = deckStore.getDck(userId, id).orElseThrow();
                return DeckLoader.fromDckText(dck, d.name(), "user:" + id);
            }
            case "sample" -> {
                String id = spec.path("id").asText();
                samples.find(id).orElseThrow(() -> new IllegalArgumentException("Sample-Deck nicht gefunden: " + id));
                if (usedSamples != null) {
                    usedSamples.add(id);
                }
                return DeckLoader.loadFile(samples.resolve(id));
            }
            default -> {
                List<SampleDeckCatalog.Entry> pool = new ArrayList<>(samples.list());
                if (usedSamples != null) {
                    pool.removeIf(e -> usedSamples.contains(e.id()));
                }
                while (!pool.isEmpty()) {
                    SampleDeckCatalog.Entry e = pool.remove(random.nextInt(pool.size()));
                    LoadedDeck d = DeckLoader.loadFile(samples.resolve(e.id()));
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
