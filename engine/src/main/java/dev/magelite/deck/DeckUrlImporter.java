package dev.magelite.deck;

import com.fasterxml.jackson.databind.JsonNode;
import dev.magelite.api.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import von Archidekt/Moxfield-Links. Liefert eine Textliste (Abschnitte "Commander", ggf. "Companion", "Deck"),
 * die dann der {@link TextDeckParser} verarbeitet. Gefaehrte: Moxfield-Board {@code companions}, Archidekt-Kategorie
 * "Companion", sonst Karten mit dem Schluesselwort Companion aus dem Sideboard.
 */
public final class DeckUrlImporter {

    private static final Pattern ARCHIDEKT = Pattern.compile("archidekt\\.com/(?:api/)?decks/(\\d+)");
    private static final Pattern MOXFIELD = Pattern.compile("moxfield\\.com/decks/([A-Za-z0-9_-]+)");

    /** {@code bracket}: vom Deck-Autor gesetzte Commander-Bracket (1-5), sonst null */
    public record Imported(String name, String text, String source, Integer bracket) {
    }

    /** Wird geworfen, wenn der Server den Abruf blockt (z.B. Moxfield/Cloudflare) - die UI kann dann selbst laden. */
    public static final class BlockedException extends Exception {
        public final String apiUrl;

        BlockedException(String apiUrl, String msg) {
            super(msg);
            this.apiUrl = apiUrl;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** API-URL zum Deck-Link (fuer Abruf durch die UI). */
    public static String apiUrl(String url) {
        Matcher a = ARCHIDEKT.matcher(url);
        if (a.find()) {
            return "https://archidekt.com/api/decks/" + a.group(1) + "/";
        }
        Matcher m = MOXFIELD.matcher(url);
        if (m.find()) {
            return "https://api2.moxfield.com/v3/decks/all/" + m.group(1);
        }
        throw new IllegalArgumentException("Unbekannter Link – unterstützt: archidekt.com/decks/…, moxfield.com/decks/…");
    }

    public Imported fetch(String url) throws Exception {
        String api = apiUrl(url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 403 || res.statusCode() == 429 || res.statusCode() == 503) {
            throw new BlockedException(api, "Abruf blockiert (HTTP " + res.statusCode() + ")");
        }
        if (res.statusCode() == 404) {
            throw new IllegalArgumentException("Deck nicht gefunden (privat oder gelöscht?)");
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        return fromJson(api, res.body());
    }

    public Imported fromJson(String apiOrUrl, String json) throws Exception {
        JsonNode root = Json.MAPPER.readTree(json);
        if (apiOrUrl.contains("archidekt")) {
            return archidekt(root);
        }
        return moxfield(root);
    }

    private static Imported archidekt(JsonNode root) {
        // Kategorien, die nicht zum Deck gehoeren (Maybeboard etc.)
        java.util.Set<String> excluded = new java.util.HashSet<>();
        java.util.Set<String> premier = new java.util.HashSet<>();
        for (JsonNode c : root.path("categories")) {
            if (!c.path("includedInDeck").asBoolean(true)) {
                excluded.add(c.path("name").asText());
            }
            if (c.path("isPremier").asBoolean(false)) {
                premier.add(c.path("name").asText());
            }
        }
        StringBuilder cmd = new StringBuilder();
        StringBuilder comp = new StringBuilder();
        StringBuilder deck = new StringBuilder();
        for (JsonNode e : root.path("cards")) {
            int qty = e.path("quantity").asInt(1);
            JsonNode card = e.path("card");
            String name = card.path("oracleCard").path("name").asText(card.path("name").asText());
            String set = card.path("edition").path("editioncode").asText("");
            String num = card.path("collectorNumber").asText("");
            boolean isCmd = false;
            boolean isComp = false;
            boolean sideboard = false;
            boolean skip = false;
            for (JsonNode cat : e.path("categories")) {
                String c = cat.asText();
                if (c.equalsIgnoreCase("Commander") || premier.contains(c)) {
                    isCmd = true;
                }
                if (c.equalsIgnoreCase("Companion")) {
                    isComp = true;
                }
                if (c.equalsIgnoreCase("Sideboard")) {
                    sideboard = true;
                }
                if (excluded.contains(c)) {
                    skip = true;
                }
            }
            if (!isCmd && (isComp || sideboard && CardLookup.isCompanion(name))) {
                line(comp, 1, name, set, num);
                continue;
            }
            if (skip && !isCmd) {
                continue;
            }
            line(isCmd ? cmd : deck, qty, name, set, num);
        }
        return new Imported(root.path("name").asText("Archidekt-Deck"), text(cmd, comp, deck), "archidekt",
                bracket(root.path("edhBracket")));
    }

    private static Imported moxfield(JsonNode root) {
        StringBuilder cmd = new StringBuilder();
        StringBuilder comp = new StringBuilder();
        StringBuilder deck = new StringBuilder();
        JsonNode boards = root.path("boards");
        boolean v3 = !boards.isMissingNode();
        board(v3 ? boards.path("commanders").path("cards") : root.path("commanders"), cmd);
        board(v3 ? boards.path("companions").path("cards") : root.path("companions"), comp);
        board(v3 ? boards.path("mainboard").path("cards") : root.path("mainboard"), deck);
        if (comp.isEmpty()) {
            // aeltere Listen: Gefaehrte im Sideboard
            Iterator<Map.Entry<String, JsonNode>> it = (v3 ? boards.path("sideboard").path("cards") : root.path("sideboard")).fields();
            while (it.hasNext()) {
                JsonNode card = it.next().getValue().path("card");
                if (CardLookup.isCompanion(card.path("name").asText())) {
                    line(comp, 1, card.path("name").asText(), card.path("set").asText(""), card.path("cn").asText(""));
                }
            }
        }
        return new Imported(root.path("name").asText("Moxfield-Deck"), text(cmd, comp, deck), "moxfield",
                bracket(root.path("bracket")));
    }

    private static String text(CharSequence cmd, CharSequence comp, CharSequence deck) {
        return "Commander\n" + cmd + (comp.isEmpty() ? "" : "\nCompanion\n" + comp) + "\nDeck\n" + deck;
    }

    /** Bracket-Feld der API (Archidekt {@code edhBracket}, Moxfield {@code bracket}); nur 1-5 zaehlt */
    private static Integer bracket(JsonNode n) {
        if (!n.canConvertToInt()) {
            return null;
        }
        int b = n.asInt();
        return b >= 1 && b <= 5 ? b : null;
    }

    private static void board(JsonNode cards, StringBuilder out) {
        Iterator<Map.Entry<String, JsonNode>> it = cards.fields();
        while (it.hasNext()) {
            JsonNode e = it.next().getValue();
            JsonNode card = e.path("card");
            line(out, e.path("quantity").asInt(1), card.path("name").asText(), card.path("set").asText(""), card.path("cn").asText(""));
        }
    }

    private static void line(StringBuilder sb, int qty, String name, String set, String num) {
        if (name == null || name.isBlank()) {
            return;
        }
        sb.append(qty).append(' ').append(name);
        if (!set.isBlank()) {
            sb.append(" (").append(set.toUpperCase()).append(')');
            if (!num.isBlank()) {
                sb.append(' ').append(num);
            }
        }
        sb.append('\n');
    }
}
