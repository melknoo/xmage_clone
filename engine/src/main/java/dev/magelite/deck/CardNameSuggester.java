package dev.magelite.deck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Meintest du ...?" fuer unbekannte Kartennamen in der Import-Vorschau (nie beim Speichern, nie auf dem Spiel-Thread).
 * <p>
 * Die Namensliste wird einmal (lazy) aus Forges Karten-DB geladen ({@link CardLookup#allNames}) und nach erstem Buchstaben und Laenge gebuckelt;
 * verglichen wird per Damerau-Levenshtein (optimal string alignment) mit kleiner Hoechstdistanz und Abbruch,
 * sobald eine Zeile die Schranke ueberschreitet. Hoechstens {@link #MAX_LOOKUPS} Zeilen und {@link #BUDGET_MS} je Vorschau.
 */
public final class CardNameSuggester {

    static final int MAX_LOOKUPS = 25;
    static final long BUDGET_MS = 1500;

    private CardNameSuggester() {
    }

    /** Ergebnis mit Vorschlaegen an den ersten {@link #MAX_LOOKUPS} unbekannten Zeilen. */
    public static TextDeckParser.Result withSuggestions(TextDeckParser.Result r) {
        if (r.issues().isEmpty()) {
            return r;
        }
        List<TextDeckParser.Issue> out = new ArrayList<>(r.issues().size());
        Map<String, String> memo = new HashMap<>();
        int lookups = 0;
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        for (TextDeckParser.Issue i : r.issues()) {
            String sug = null;
            if ("unknown".equals(i.kind()) && lookups < MAX_LOOKUPS && System.currentTimeMillis() < deadline) {
                if (memo.containsKey(i.name())) {
                    sug = memo.get(i.name());
                } else {
                    lookups++;
                    try {
                        sug = suggest(i.name());
                    } catch (RuntimeException | LinkageError e) {
                        sug = null; // nur ein Hinweis - Vorschau nie daran scheitern lassen
                    }
                    memo.put(i.name(), sug);
                }
            }
            out.add(new TextDeckParser.Issue(i.line(), i.count(), i.name(), sug, i.kind()));
        }
        return r.withIssues(out);
    }

    /** Namensindex vorbauen (Warmup-Thread), damit die erste Vorschau mit unbekannter Karte nicht wartet. */
    public static void warmup() {
        Index idx = Holder.INDEX;
        if (idx.buckets.isEmpty()) {
            throw new IllegalStateException("Kartennamen fehlen");
        }
    }

    /** Naechster bekannter Kartenname oder null (zu weit weg / nichts Passendes). */
    public static String suggest(String name) {
        if (name == null) {
            return null;
        }
        String n = norm(name);
        if (n.length() < 3 || n.length() > 141) {
            return null;
        }
        int max = n.length() <= 5 ? 1 : n.length() <= 12 ? 2 : 3;
        Index idx = Holder.INDEX;
        String best = null;
        int bestDist = max + 1;
        char first = n.charAt(0);
        for (int len = n.length() - max; len <= n.length() + max; len++) {
            List<String[]> bucket = idx.buckets.get(key(first, len));
            if (bucket == null) {
                continue;
            }
            for (String[] e : bucket) {
                int d = distance(n, e[0], Math.min(max, bestDist));
                if (d < bestDist || (d == bestDist && best != null && Math.abs(e[0].length() - n.length()) < Math.abs(norm(best).length() - n.length()))) {
                    if (d <= max) {
                        best = e[1];
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }

    static String norm(String s) {
        return s.replace('’', '\'').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static long key(char first, int len) {
        return ((long) first << 20) | (len & 0xFFFFF);
    }

    /**
     * Damerau-Levenshtein (optimal string alignment); liefert {@code limit + 1}, sobald die Distanz sicher groesser
     * als {@code limit} ist.
     */
    static int distance(String a, String b, int limit) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > limit) {
            return limit + 1;
        }
        int[] prev2 = new int[m + 1];
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                int cost = ca == cb ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
                if (v < rowMin) {
                    rowMin = v;
                }
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    private static final class Index {
        /** (erster Buchstabe, Laenge) -&gt; [kleingeschrieben, Original] */
        final Map<Long, List<String[]>> buckets = new HashMap<>();

        Index(Iterable<String> names) {
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String l = norm(name);
                buckets.computeIfAbsent(key(l.charAt(0), l.length()), k -> new ArrayList<>()).add(new String[]{l, name});
            }
        }
    }

    /** Lazy: erst bei der ersten Vorschau mit unbekannter Karte (HTTP-Thread). */
    private static final class Holder {
        static final Index INDEX = new Index(CardLookup.allNames());
    }
}
