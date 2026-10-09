package dev.magelite.game;

import dev.magelite.view.dto.PromptDto;
import forge.game.card.Card;
import forge.game.replacement.ReplacementEffect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Hilfen für die Ersatzeffekt-Wahl. Forge-Ablauf ({@code ReplacementHandler}): Effekt wählen
 * ({@code chooseSingleReplacementEffect}), ein optionaler Effekt fragt danach selbst ({@code confirmReplacementEffect},
 * "Dredge 2?"), bei Nein kommt die Wahl erneut mit den übrigen Effekten. Gleiche Effekte (gleicher Regeltext) werden zu
 * einer Gruppe zusammengefasst, damit die UI sie als einen Eintrag zeigt und die Engine die Ja/Nein-Kette selbst
 * beantworten kann.
 */
final class ReplacementAssist {

    private ReplacementAssist() {
    }

    /** Regeltext eines Ersatzeffekts (Gruppierungs-Schlüssel). */
    static String rule(ReplacementEffect re) {
        String d = re.getDescription();
        if (d == null || d.isBlank()) {
            d = re.toString();
        }
        d = d == null ? "" : d.trim();
        // Forge stellt den Kartennamen voran ("Golgari Brownscale - Dredge 2"); ohne ihn gruppieren gleiche Effekte
        Card host = re.getHostCard();
        if (host != null && d.startsWith(host.getName() + " - ")) {
            d = d.substring(host.getName().length() + 3).trim();
        }
        return d;
    }

    /** "you may" bzw. Forge-Parameter {@code Optional}: der Effekt fragt nach der Wahl selbst. */
    static boolean optional(ReplacementEffect re) {
        return re.hasParam("Optional") || Pattern.compile("(?i)\\byou may\\b").matcher(rule(re)).find();
    }

    /**
     * Gruppen nach Regeltext (Reihenfolge der ersten Vorkommen); Schlüssel je Effekt = Index in {@code effects}.
     */
    static List<PromptDto.ReplGroup> groups(List<ReplacementEffect> effects, Function<Card, java.util.UUID> wireId) {
        Map<String, List<PromptDto.ReplSource>> byRule = new LinkedHashMap<>();
        Map<String, Boolean> optional = new LinkedHashMap<>();
        for (int i = 0; i < effects.size(); i++) {
            ReplacementEffect re = effects.get(i);
            Card host = re.getHostCard();
            String rule = rule(re);
            byRule.computeIfAbsent(rule, r -> new ArrayList<>()).add(new PromptDto.ReplSource(String.valueOf(i),
                    host == null ? "?" : host.getName(), host == null ? null : wireId.apply(host)));
            optional.merge(rule, optional(re), Boolean::logicalOr);
        }
        List<PromptDto.ReplGroup> out = new ArrayList<>();
        byRule.forEach((rule, sources) -> out.add(new PromptDto.ReplGroup(rule, label(rule), optional.get(rule), sources,
                cause(rule), sources.stream().map(PromptDto.ReplSource::name).distinct().count() == 1)));
        return out;
    }

    private static final List<Map.Entry<Pattern, String>> CAUSES = List.of(
            Map.entry(Pattern.compile("(?i)\\bwould (draw|draws)\\b"), "Karte ziehen"),
            Map.entry(Pattern.compile("(?i)\\bwould (discard|be discarded)\\b"), "Abwerfen"),
            Map.entry(Pattern.compile("(?i)\\bwould (mill|be milled)\\b"), "Mahlen"),
            Map.entry(Pattern.compile("(?i)\\bwould die\\b|\\bwould be destroyed\\b"), "Sterben"),
            Map.entry(Pattern.compile("(?i)\\bwould be put into [a-z' ]*graveyard\\b"), "Auf den Friedhof"),
            Map.entry(Pattern.compile("(?i)\\bwould (be dealt|deal) (combat )?damage\\b"), "Schaden"),
            Map.entry(Pattern.compile("(?i)\\bwould gain life\\b"), "Lebensgewinn"),
            Map.entry(Pattern.compile("(?i)\\bwould lose life\\b"), "Lebensverlust"),
            Map.entry(Pattern.compile("(?i)\\bwould (create|be created)\\b"), "Spielstein erzeugen"),
            Map.entry(Pattern.compile("(?i)\\bwould (put|be put|have|get)\\b[^.]*\\bcounters?\\b"), "Marken"),
            Map.entry(Pattern.compile("(?i)\\bwould enter\\b|\\bas [^.]* enters\\b"), "Ins Spiel kommen"),
            Map.entry(Pattern.compile("(?i)\\bwould be exiled\\b"), "Exil"),
            Map.entry(Pattern.compile("(?i)\\bwould leave\\b"), "Spielfeld verlassen"));

    /** Ersetztes Ereignis aus dem Regeltext ("If you would draw a card, ..." -&gt; "Karte ziehen"), sonst null. */
    static String cause(String rule) {
        if (rule == null) {
            return null;
        }
        for (Map.Entry<Pattern, String> e : CAUSES) {
            if (e.getKey().matcher(rule).find()) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Kurzname für Anzeigen: Regeltext bis zum Erinnerungstext ("Dredge 2 (If you ...)" -&gt; "Dredge 2"). */
    static String label(String rule) {
        int i = rule.indexOf(" (");
        String s = i > 0 ? rule.substring(0, i) : rule;
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    /** Alle Items gleicher Name und gleiche Regel (z.B. Token-Kopien): Reihenfolge egal. */
    static boolean allIdentical(List<PromptDto.ReplGroup> groups) {
        if (groups.size() != 1) {
            return false;
        }
        List<PromptDto.ReplSource> src = groups.get(0).sources();
        return src.stream().map(PromptDto.ReplSource::name).distinct().count() == 1;
    }
}
