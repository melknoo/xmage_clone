package dev.magelite.game;

import dev.magelite.view.RichText;
import dev.magelite.view.dto.PromptDto;
import mage.ObjectColor;
import mage.abilities.SpecialAction;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sonderbezahlung (Convoke, Delve, Improvise, Assist). XMage bietet sie im Mana-Prompt nur ueber die Antwort
 * {@code "special"} an (kein {@code SPECIAL_BUTTON}): danach Wahl der Sonderaktion ({@code CHOOSE_ABILITY}), Ziel
 * ({@code PICK_TARGET}) und bei Convoke ggf. die Farbe. Die Aktionen liegen nur waehrend der Bezahlung in
 * {@code getSpecialActions()} (XMage fuegt sie vor jedem {@code playMana} hinzu und raeumt danach). Die
 * {@code *SpecialAction}-Klassen sind package-private, daher der Vergleich ueber den Klassennamen.
 * Wichtig: Nach der ersten Sonderbezahlung sind Manafaehigkeiten (Laender) fuer diesen Zauber gesperrt.
 */
final class SpecialPay {

    static final String CONVOKE = "ConvokeSpecialAction";
    private static final String COLOR_MSG = "Choose mana color to reduce from";

    private SpecialPay() {
    }

    /** Sonderaktionen fuers Bezahlen, die der Spieler gerade nutzen kann. */
    static Map<UUID, SpecialAction> manaActions(Game game, UUID playerId) {
        try {
            return game.getState().getSpecialActions().getControlledBy(playerId, true);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    static boolean isConvoke(SpecialAction sa) {
        return CONVOKE.equals(sa.getClass().getSimpleName());
    }

    static String label(SpecialAction sa) {
        return switch (sa.getClass().getSimpleName()) {
            case "ConvokeSpecialAction" -> "Einberufen";
            case "DelveSpecialAction" -> "Wühlen";
            case "ImproviseSpecialAction" -> "Improvisieren";
            case "AssistSpecialAction" -> "Beistand";
            default -> "Sonderbezahlung";
        };
    }

    private static String hint(SpecialAction sa) {
        return switch (sa.getClass().getSimpleName()) {
            case "ConvokeSpecialAction" -> " – Kreatur tappen";
            case "DelveSpecialAction" -> " – Karten aus dem Friedhof exilieren";
            case "ImproviseSpecialAction" -> " – Artefakt tappen";
            case "AssistSpecialAction" -> " – Mitspieler zahlt mit";
            default -> "";
        };
    }

    /** PLAY_MANA: Knopf-Beschriftung und per Klick einberufbare Kreaturen. */
    static void describe(Game game, UUID playerId, PromptDto p) {
        Map<UUID, SpecialAction> actions = manaActions(game, playerId);
        if (actions.isEmpty()) {
            return;
        }
        p.specialBtn = actions.values().stream().map(SpecialPay::label).distinct().collect(Collectors.joining(" / "));
        for (SpecialAction sa : actions.values()) {
            if (isConvoke(sa) && !sa.getTargets().isEmpty()) {
                try {
                    p.specialTargets = new ArrayList<>(sa.getTargets().get(0).possibleTargets(playerId, sa, game));
                } catch (RuntimeException ignored) {
                    // nur Komfort - Knopf bleibt
                }
            }
        }
        if (p.specialTargets != null && !p.specialTargets.isEmpty() && p.secondMessage == null) {
            p.secondMessage = RichText.parse("Kreatur anklicken = einberufen. Länder zuerst tappen – danach sind sie für diesen Zauber gesperrt.");
        }
    }

    /** CHOOSE_ABILITY nach "special": Sonderaktionen deutsch beschriften. @return true, wenn nur Sonderaktionen zur Wahl stehen */
    static boolean relabel(Game game, List<? extends mage.abilities.Ability> abilities, PromptDto p) {
        if (abilities == null || p.choices == null) {
            return false;
        }
        boolean allSpecial = !abilities.isEmpty();
        List<PromptDto.Item> items = new ArrayList<>(p.choices.size());
        for (PromptDto.Item it : p.choices) {
            SpecialAction sa = null;
            for (mage.abilities.Ability a : abilities) {
                if (a instanceof SpecialAction s && a.getId().toString().equals(it.id())) {
                    sa = s;
                }
            }
            items.add(sa == null ? it : new PromptDto.Item(it.id(), label(sa) + hint(sa), null, null, null));
        }
        for (mage.abilities.Ability a : abilities) {
            if (!(a instanceof SpecialAction)) {
                allSpecial = false;
            }
        }
        p.choices = items;
        return allSpecial;
    }

    /** CHOOSE_ABILITY im Einberufen-Makro: id der Convoke-Aktion unter den angebotenen. */
    static UUID convokeChoice(Game game, UUID playerId, PromptDto p) {
        if (p.choices == null) {
            return null;
        }
        for (SpecialAction sa : manaActions(game, playerId).values()) {
            if (!isConvoke(sa)) {
                continue;
            }
            String id = sa.getId().toString();
            for (PromptDto.Item it : p.choices) {
                if (id.equals(it.id())) {
                    return sa.getId();
                }
            }
        }
        return null;
    }

    static boolean isConvokeColor(PromptDto p) {
        return "CHOOSE_CHOICE".equals(p.kind) && p.choice != null && p.messageText != null && p.messageText.startsWith(COLOR_MSG);
    }

    /**
     * Farbe, die die eingeberufene Kreatur bezahlt. Nie "Colorless", solange eine Farbe angeboten wird (XMage bietet
     * nur noch offene Farben der Kreatur an; generisch zahlt jede andere Kreatur genauso). Unter den Farben die mit dem
     * groessten Engpass: benoetigt minus weitere ungetappte Kreaturen dieser Farbe; Gleichstand -> mehr benoetigt, WUBRG.
     *
     * @return Antwort (Schluessel bzw. Wert des Items) oder null
     */
    static String pickColor(Game game, UUID playerId, UUID tapped, String unpaidText, PromptDto.ChoiceDto choice) {
        if (choice == null || choice.items == null) {
            return null;
        }
        Map<AutoPayer.Color, Integer> need = AutoPayer.parseCost(unpaidText).colored();
        Map<AutoPayer.Color, Integer> supply = new EnumMap<>(AutoPayer.Color.class);
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, playerId, game)) {
            if (perm.isTapped() || perm.getId().equals(tapped)) {
                continue;
            }
            ObjectColor c = perm.getColor(game);
            if (c.isWhite()) supply.merge(AutoPayer.Color.W, 1, Integer::sum);
            if (c.isBlue()) supply.merge(AutoPayer.Color.U, 1, Integer::sum);
            if (c.isBlack()) supply.merge(AutoPayer.Color.B, 1, Integer::sum);
            if (c.isRed()) supply.merge(AutoPayer.Color.R, 1, Integer::sum);
            if (c.isGreen()) supply.merge(AutoPayer.Color.G, 1, Integer::sum);
        }
        PromptDto.ChoiceItem best = null;
        int bestScore = Integer.MIN_VALUE;
        int bestNeed = -1;
        for (AutoPayer.Color col : List.of(AutoPayer.Color.W, AutoPayer.Color.U, AutoPayer.Color.B, AutoPayer.Color.R, AutoPayer.Color.G)) {
            String name = AutoPayer.colorName(col);
            PromptDto.ChoiceItem item = choice.items.stream()
                    .filter(i -> name.equalsIgnoreCase(i.value()) || name.equalsIgnoreCase(i.key())).findFirst().orElse(null);
            if (item == null) {
                continue;
            }
            int n = need.getOrDefault(col, 0);
            int score = n - supply.getOrDefault(col, 0);
            if (score > bestScore || (score == bestScore && n > bestNeed)) {
                best = item;
                bestScore = score;
                bestNeed = n;
            }
        }
        if (best == null) {
            return null;
        }
        return choice.keyed ? best.key() : best.value();
    }
}
