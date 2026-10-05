package dev.magelite.deck;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST fuer den Deck-Import: Vorschau (Text/URL) und Speichern.
 */
public final class DeckRoutes implements HttpServer.Module {

    private final DeckStore store;
    private final DeckUrlImporter urls = new DeckUrlImporter();

    public DeckRoutes(DeckStore store) {
        this.store = store;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/decks/parse", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            ctx.json(preview(b.path("text").asText(""), text(b, "name"), commanders(b)));
        });
        app.post("/api/decks/url", this::fromUrl);
        app.post("/api/decks", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            String text = b.path("text").asText("");
            TextDeckParser.Result r = TextDeckParser.parse(text, text(b, "name"), commanders(b));
            if (r.commanders().isEmpty()) {
                throw new IllegalArgumentException("Bitte zuerst einen Commander wählen");
            }
            LoadedDeck loaded = DeckLoader.fromLists(r.toLists(), "import", "");
            TextDeckParser.Resolved first = r.commanders().get(0);
            Long id = b.hasNonNull("id") ? b.get("id").asLong() : null;
            long userId = Auth.user(ctx).id();
            long saved = store.save(userId, id, r.name(), r.commanders().stream().map(TextDeckParser.Resolved::name).toList(),
                    SampleDeckCatalog.colorsOf(r.commanders().stream().map(TextDeckParser.Resolved::name).toList()),
                    first.set(), first.number(), text(b, "source") == null ? "text" : text(b, "source"), text(b, "sourceUrl"),
                    r.toDck(), r.cardCount(), loaded.valid(), loaded.validationErrors());
            ctx.json(store.get(userId, saved).orElseThrow());
        });
        app.get("/api/decks/{id}/text", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            String dck = store.getDck(Auth.user(ctx).id(), id).orElseThrow(() -> new IllegalArgumentException("Deck nicht gefunden"));
            ctx.json(Map.of("text", dckToText(dck)));
        });
    }

    private void fromUrl(Context ctx) throws Exception {
        JsonNode b = Json.MAPPER.readTree(ctx.body());
        String url = b.path("url").asText("").trim();
        DeckUrlImporter.Imported imp;
        try {
            if (b.hasNonNull("json")) {
                imp = urls.fromJson(url, b.get("json").asText());
            } else {
                imp = urls.fetch(url);
            }
        } catch (DeckUrlImporter.BlockedException e) {
            ctx.status(409).json(Map.of("error", e.getMessage(), "blocked", true, "apiUrl", e.apiUrl));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>(preview(imp.text(), imp.name(), null));
        out.put("text", imp.text());
        out.put("source", imp.source());
        out.put("sourceUrl", url);
        ctx.json(out);
    }

    private static Map<String, Object> preview(String text, String name, List<String> commanders) throws Exception {
        TextDeckParser.Result r = TextDeckParser.parse(text, name, commanders);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", r.name());
        out.put("commanders", r.commanders());
        out.put("cardCount", r.cardCount());
        out.put("unknown", r.unknown());
        out.put("unfinished", r.unfinished());
        out.put("needsCommander", r.needsCommander());
        out.put("candidates", r.candidates());
        List<Map<String, Object>> cards = new ArrayList<>();
        for (TextDeckParser.Resolved c : r.main()) {
            cards.add(Map.of("name", c.name(), "set", c.set(), "num", c.number(), "count", c.count()));
        }
        out.put("cards", cards);
        if (!r.commanders().isEmpty()) {
            LoadedDeck loaded = DeckLoader.fromLists(r.toLists(), "preview", "");
            out.put("valid", loaded.valid());
            out.put("validation", loaded.validationErrors());
            List<String> names = r.commanders().stream().map(TextDeckParser.Resolved::name).toList();
            out.put("colors", SampleDeckCatalog.colorsOf(names));
            out.put("commanderSet", r.commanders().get(0).set());
            out.put("commanderNum", r.commanders().get(0).number());
        } else {
            out.put("valid", false);
        }
        return out;
    }

    /** .dck -> lesbarer Text (zum Bearbeiten). */
    static String dckToText(String dck) {
        StringBuilder cmd = new StringBuilder();
        StringBuilder deck = new StringBuilder();
        for (String line : dck.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("NAME:") || l.startsWith("LAYOUT")) {
                continue;
            }
            boolean sb = l.startsWith("SB:");
            if (sb) {
                l = l.substring(3).trim();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\s*\\[([^]:]+):([^]]+)]\\s*(.+)$").matcher(l);
            if (m.matches()) {
                (sb ? cmd : deck).append(m.group(1)).append(' ').append(m.group(4)).append(" (").append(m.group(2)).append(") ").append(m.group(3)).append('\n');
            }
        }
        return "Commander\n" + cmd + "\nDeck\n" + deck;
    }

    private static String text(JsonNode b, String field) {
        return b.hasNonNull(field) && !b.get(field).asText().isBlank() ? b.get(field).asText() : null;
    }

    private static List<String> commanders(JsonNode b) {
        if (!b.path("commanders").isArray() || b.path("commanders").isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        b.path("commanders").forEach(n -> out.add(n.asText()));
        return out;
    }
}
