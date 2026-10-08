package dev.magelite.deck;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Auth;
import dev.magelite.api.HttpServer;
import dev.magelite.api.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST fuer den Deck-Import: Vorschau (Text/URL) und Speichern.
 */
public final class DeckRoutes implements HttpServer.Module {

    private static final Logger LOG = Logger.getLogger(DeckRoutes.class);
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
            TextDeckParser.checkSize(text);
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
                    r.toDck(), r.cardCount(), loaded.valid(), loaded.validationErrors(), bracketOf(r));
            if (b.has("folder") || b.has("bracket")) {
                store.patchMeta(userId, saved, text(b, "folder") == null ? (b.has("folder") ? "" : null) : text(b, "folder"),
                        b.hasNonNull("bracket") ? b.get("bracket").asInt() : null);
            }
            ctx.json(store.get(userId, saved).orElseThrow());
        });
        // Ordner/Bracket aendern ohne Neuspeichern; bracket 0 = Vorschlag gilt
        app.post("/api/decks/{id}/meta", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            long userId = Auth.user(ctx).id();
            long id = Long.parseLong(ctx.pathParam("id"));
            String folder = b.has("folder") ? b.path("folder").asText("") : null;
            Integer bracket = b.hasNonNull("bracket") ? b.get("bracket").asInt() : null;
            store.patchMeta(userId, id, folder, bracket);
            ctx.json(store.get(userId, id).orElseThrow(() -> new IllegalArgumentException("Deck nicht gefunden")));
        });
        // Reihenfolge eines Ordners (Drag & Drop): {folder, ids[]}
        app.post("/api/decks/order", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            List<Long> ids = new ArrayList<>();
            b.path("ids").forEach(n -> ids.add(n.asLong()));
            if (ids.size() > 1000) {
                throw new IllegalArgumentException("Zu viele Decks");
            }
            ctx.json(Map.of("decks", store.reorder(Auth.user(ctx).id(), b.path("folder").asText(""), ids)));
        });
        // Ordner umbenennen; to = '' loest ihn auf
        app.post("/api/decks/folders/rename", ctx -> {
            JsonNode b = Json.MAPPER.readTree(ctx.body());
            int n = store.renameFolder(Auth.user(ctx).id(), b.path("from").asText(""), b.path("to").asText(""));
            ctx.json(Map.of("decks", n));
        });
        app.get("/api/decks/{id}/text", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            String dck = store.getDck(Auth.user(ctx).id(), id).orElseThrow(() -> new IllegalArgumentException("Deck nicht gefunden"));
            ctx.json(Map.of("text", dckToText(dck)));
        });
    }

    /**
     * Bracket-Vorschlag fuer Decks aus der Zeit vor V6 nachtragen (einmalig, im Hintergrund nach dem Start; braucht die
     * Karten-DB).
     */
    public void backfillBrackets() {
        Thread t = new Thread(() -> {
            var todo = store.withoutBracketAuto();
            int done = 0;
            for (var e : todo.entrySet()) {
                try {
                    TextDeckParser.Result r = TextDeckParser.parse(dckToText(e.getValue()), null, null);
                    store.setBracketAuto(e.getKey(), bracketOf(r));
                    done++;
                } catch (RuntimeException ex) {
                    LOG.warn("Bracket-Vorschlag fuer Deck " + e.getKey() + " fehlgeschlagen: " + ex.getMessage());
                }
            }
            if (!todo.isEmpty()) {
                LOG.info("Bracket-Vorschlag nachgetragen: " + done + "/" + todo.size() + " Decks");
            }
        }, "bracket-backfill");
        t.setDaemon(true);
        t.start();
    }

    static BracketAnalyzer.Result bracketOf(TextDeckParser.Result r) {
        List<TextDeckParser.Resolved> all = new ArrayList<>(r.commanders());
        all.addAll(r.main());
        return BracketAnalyzer.analyze(all);
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
        if (imp.bracket() != null) {
            out.put("bracket", imp.bracket());
        }
        ctx.json(out);
    }

    private static Map<String, Object> preview(String text, String name, List<String> commanders) throws Exception {
        TextDeckParser.checkSize(text);
        // Vorschlaege ("Meintest du ...?") nur hier in der Vorschau, nie beim Speichern
        TextDeckParser.Result r = CardNameSuggester.withSuggestions(TextDeckParser.parse(text, name, commanders));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", r.name());
        out.put("commanders", r.commanders());
        out.put("cardCount", r.cardCount());
        out.put("unknown", r.unknown());
        out.put("unfinished", r.unfinished());
        List<Map<String, Object>> issues = new ArrayList<>();
        for (TextDeckParser.Issue i : r.issues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", i.line());
            m.put("count", i.count());
            m.put("name", i.name());
            if (i.suggestion() != null) {
                m.put("suggestion", i.suggestion());
            }
            m.put("kind", i.kind());
            issues.add(m);
        }
        out.put("issues", issues);
        out.put("needsCommander", r.needsCommander());
        out.put("candidates", r.candidates());
        List<Map<String, Object>> cards = new ArrayList<>();
        for (TextDeckParser.Resolved c : r.main()) {
            String type = r.typeOf(c.name());
            cards.add(Map.of("name", c.name(), "set", c.set(), "num", c.number(), "count", c.count(), "type", type == null ? "other" : type));
        }
        out.put("cards", cards);
        BracketAnalyzer.Result bracket = bracketOf(r);
        out.put("bracketAuto", bracket.bracket());
        out.put("bracketInfo", bracket.reasons());
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
