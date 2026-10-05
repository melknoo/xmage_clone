package dev.magelite.game;

import dev.magelite.view.RichText;
import dev.magelite.view.dto.PromptDto;
import mage.MageObject;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.choices.Choice;
import mage.game.Game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hilfen fuer die Ersatzeffekt-Wahl ("Choose replacement effect to resolve first").
 * <p>
 * XMage-Ablauf ({@code ContinuousEffects.replaceEvent}): Effekt waehlen, der Effekt fragt ggf. selbst
 * ("Dredge X?"), bei Nein erneute Wahl mit den uebrigen Effekten; beim letzten nur noch die Frage.
 * Gleiche Effekte (gleicher Regeltext) werden zu einer Gruppe zusammengefasst, damit die UI sie als einen
 * Eintrag zeigen und {@code GameHost} die Ja/Nein-Kette selbst beantworten kann.
 */
final class ReplacementAssist {

    /** Text aus {@code HumanPlayer.initReplacementDialog} */
    private static final String CHOICE_MESSAGE = "Choose replacement effect to resolve first";
    /** {@code object.getIdName() + ": " + rule} aus {@code ContinuousEffects.prepareReplacementEffectMaps} */
    private static final Pattern ITEM = Pattern.compile("^(.+?) \\[\\w+\\]: (.+)$", Pattern.DOTALL);
    private static final Pattern OPTIONAL = Pattern.compile("(?i)\\byou may\\b");
    private static final String REPLACE_CLASS = "mage.abilities.effects.ContinuousEffects";
    private static final StackWalker WALKER = StackWalker.getInstance();

    private ReplacementAssist() {
    }

    static boolean isReplacementChoice(Choice c) {
        return c != null && c.isKeyChoice() && CHOICE_MESSAGE.equals(c.getMessage());
    }

    /** Gruppiert die Items nach Regeltext (Reihenfolge der ersten Vorkommen bleibt erhalten). */
    static List<PromptDto.ReplGroup> groups(PromptDto.ChoiceDto choice) {
        Map<String, List<PromptDto.ReplSource>> byRule = new LinkedHashMap<>();
        for (PromptDto.ChoiceItem it : choice.items) {
            String name = it.value();
            String rule = it.value().trim();
            Matcher m = ITEM.matcher(rule);
            if (m.matches()) {
                name = m.group(1).trim();
                rule = m.group(2).trim();
            }
            UUID objectId = null;
            List<String> hints = it.hints();
            if (hints != null && hints.size() >= 2 && "GAME_OBJECT".equals(hints.get(0)) && hints.get(1) != null) {
                try {
                    objectId = UUID.fromString(hints.get(1));
                } catch (IllegalArgumentException ignored) {
                    // kein UUID
                }
            }
            byRule.computeIfAbsent(rule, r -> new ArrayList<>()).add(new PromptDto.ReplSource(it.key(), name, objectId));
        }
        List<PromptDto.ReplGroup> out = new ArrayList<>();
        byRule.forEach((rule, sources) -> out.add(new PromptDto.ReplGroup(rule, label(rule), OPTIONAL.matcher(rule).find(), sources)));
        return out;
    }

    /** Kurzname fuer Anzeigen: Regeltext bis zum Erinnerungstext ("Dredge 2 (If you ...)" -> "Dredge 2"). */
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

    /**
     * Spiel-Thread: stammt die aktuelle Frage aus einem Ersatzeffekt? Listener laufen synchron, also steht
     * {@code ContinuousEffects.replaceEvent} dann auf dem Stack.
     */
    static boolean inReplaceEvent() {
        return WALKER.walk(s -> s.anyMatch(f -> "replaceEvent".equals(f.getMethodName()) && REPLACE_CLASS.equals(f.getClassName())));
    }

    /** Objekt-IDs, die in der Prompt-Nachricht verlinkt sind ({@code object_id} aus {@code getLogName()}). */
    static Set<UUID> objIds(PromptDto p) {
        Set<UUID> out = new java.util.HashSet<>();
        if (p.message != null) {
            for (Map<String, Object> seg : p.message) {
                Object o = seg.get("obj");
                if (o != null) {
                    try {
                        out.add(UUID.fromString(o.toString()));
                    } catch (IllegalArgumentException ignored) {
                        // kein UUID
                    }
                }
            }
        }
        return out;
    }

    /** Hat eines der Objekte eine Faehigkeit mit einem der Regeltexte (inkl. verliehener Faehigkeiten)? */
    static boolean hasRule(Game game, Collection<UUID> objIds, Set<String> rules) {
        if (rules.isEmpty()) {
            return false;
        }
        for (UUID id : objIds) {
            MageObject o = game.getObject(id);
            if (o == null) {
                continue;
            }
            Collection<Ability> abilities = o instanceof Card c ? c.getAbilities(game) : o.getAbilities();
            for (Ability a : abilities) {
                String rule = RichText.plain(a.getRule(o.getName())).trim();
                if (rules.contains(rule)) {
                    return true;
                }
            }
        }
        return false;
    }
}
