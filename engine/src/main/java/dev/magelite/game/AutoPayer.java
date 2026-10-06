package dev.magelite.game;

import mage.Mana;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plant automatisches Bezahlen von Manakosten: waehlt pro Schritt eine Manaquelle (und Farbe).
 * XMage verlangt sonst, jede Quelle einzeln anzuklicken.
 * <p>
 * Strategie: zuerst die am staerksten eingeschraenkte farbige Anforderung mit der "duemmsten"
 * passenden Quelle (wenigste Farboptionen) bedienen, generische Kosten zuletzt mit farblosen/
 * monochromen Quellen. Wird jeden Schritt neu berechnet (XMage meldet die Restkosten im Prompt).
 */
public final class AutoPayer {

    private static final Pattern PAY = Pattern.compile("Pay\\s+((?:\\{[^}]+\\})+)");
    private static final Pattern SYM = Pattern.compile("\\{([^}]+)\\}");

    public enum Color { W, U, B, R, G, C }

    public record Step(UUID sourceId, Color color) {
    }

    record Source(UUID id, Set<Color> colors, boolean any, int amount) {
        int flexibility() {
            return any ? 10 : colors.size();
        }
    }

    record Cost(Map<Color, Integer> colored, List<Set<Color>> hybrid, int generic) {
        boolean isEmpty() {
            return colored.values().stream().allMatch(v -> v == 0) && hybrid.isEmpty() && generic == 0;
        }
    }

    private AutoPayer() {
    }

    static Cost parseCost(String promptText) {
        Map<Color, Integer> colored = new LinkedHashMap<>();
        List<Set<Color>> hybrid = new ArrayList<>();
        int generic = 0;
        if (promptText == null) {
            return new Cost(colored, hybrid, 0);
        }
        Matcher m = PAY.matcher(promptText);
        if (!m.find()) {
            return new Cost(colored, hybrid, 0);
        }
        Matcher s = SYM.matcher(m.group(1));
        while (s.find()) {
            String sym = s.group(1).toUpperCase();
            if (sym.matches("\\d+")) {
                generic += Integer.parseInt(sym);
            } else if (sym.length() == 1 && "WUBRGC".contains(sym)) {
                colored.merge(Color.valueOf(sym), 1, Integer::sum);
            } else if (sym.contains("/")) {
                // Hybrid (W/U), Monohybrid (2/W), Phyrexisch (G/P)
                Set<Color> opts = EnumSet.noneOf(Color.class);
                boolean genericOption = false;
                for (String part : sym.split("/")) {
                    if (part.length() == 1 && "WUBRGC".contains(part)) {
                        opts.add(Color.valueOf(part));
                    } else if (part.matches("\\d+")) {
                        genericOption = true;
                    }
                }
                if (opts.size() == 1 && !genericOption) {
                    colored.merge(opts.iterator().next(), 1, Integer::sum);
                } else if (!opts.isEmpty()) {
                    if (genericOption) {
                        opts.addAll(EnumSet.allOf(Color.class));
                    }
                    hybrid.add(opts);
                }
            }
            // X, S (Schnee) etc. ignorieren -> wird manuell bezahlt
        }
        return new Cost(colored, hybrid, generic);
    }

    static List<Source> sources(Game game, UUID playerId, Collection<UUID> usable) {
        List<Source> out = new ArrayList<>();
        for (UUID id : usable) {
            Permanent perm = game.getPermanent(id);
            if (perm == null || !playerId.equals(perm.getControllerId())) {
                continue;
            }
            Set<Color> colors = EnumSet.noneOf(Color.class);
            boolean any = false;
            int amount = 0;
            for (ActivatedManaAbilityImpl ab : perm.getAbilities().getActivatedManaAbilities(Zone.BATTLEFIELD)) {
                if (!ab.canActivate(playerId, game).canActivate()) {
                    continue;
                }
                for (Mana mana : ab.getNetMana(game)) {
                    if (mana.getWhite() > 0) colors.add(Color.W);
                    if (mana.getBlue() > 0) colors.add(Color.U);
                    if (mana.getBlack() > 0) colors.add(Color.B);
                    if (mana.getRed() > 0) colors.add(Color.R);
                    if (mana.getGreen() > 0) colors.add(Color.G);
                    if (mana.getColorless() > 0) colors.add(Color.C);
                    if (mana.getAny() > 0) any = true;
                    amount = Math.max(amount, mana.count());
                }
            }
            if (any || !colors.isEmpty()) {
                out.add(new Source(id, colors, any, Math.max(1, amount)));
            }
        }
        return out;
    }

    /**
     * @return naechster Klick oder null, wenn nichts Sinnvolles moeglich ist
     */
    public static Step next(Game game, UUID playerId, String promptText, Collection<UUID> usable) {
        return next(game, playerId, promptText, usable, false);
    }

    /**
     * @param partial Teilzahlung (Rest per Sonderbezahlung wie Convoke): nicht bezahlbare Farben ueberspringen statt
     *                aufzugeben, aber keine Quelle tappen, die nichts mehr beitraegt
     * @return naechster Klick oder null, wenn nichts Sinnvolles moeglich ist
     */
    public static Step next(Game game, UUID playerId, String promptText, Collection<UUID> usable, boolean partial) {
        Cost cost = parseCost(promptText);
        if (cost.isEmpty()) {
            return null;
        }
        List<Source> sources = sources(game, playerId, usable);
        if (sources.isEmpty()) {
            return null;
        }
        // 1) farbige Anforderungen: die mit den wenigsten passenden Quellen zuerst
        Color best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Map.Entry<Color, Integer> e : cost.colored().entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            int n = (int) sources.stream().filter(s -> canMake(s, e.getKey())).count();
            if (n == 0) {
                if (partial) {
                    continue; // zahlt die Sonderbezahlung
                }
                return null; // nicht bezahlbar -> manuell
            }
            if (n < bestCount) {
                bestCount = n;
                best = e.getKey();
            }
        }
        if (best != null) {
            Color c = best;
            Source pick = sources.stream().filter(s -> canMake(s, c))
                    .min((a, b) -> compare(a, b, cost))
                    .orElse(null);
            return pick == null ? null : new Step(pick.id(), c);
        }
        // 2) Hybrid
        if (!cost.hybrid().isEmpty()) {
            Set<Color> opts = cost.hybrid().get(0);
            Source pick = null;
            Color col = null;
            for (Source s : sources) {
                for (Color c : opts) {
                    if (canMake(s, c) && (pick == null || compare(s, pick, cost) < 0)) {
                        pick = s;
                        col = c;
                    }
                }
            }
            return pick == null ? null : new Step(pick.id(), col);
        }
        // 3) generisch: farblose / unflexible Quellen zuerst
        if (partial && cost.generic() == 0) {
            return null;
        }
        Source pick = sources.stream().min((a, b) -> compare(a, b, cost)).orElse(null);
        if (pick == null) {
            return null;
        }
        Color col = pick.colors().contains(Color.C) ? Color.C : pick.colors().isEmpty() ? Color.C : pick.colors().iterator().next();
        return new Step(pick.id(), col);
    }

    private static boolean canMake(Source s, Color c) {
        // {C} verlangt farbloses Mana - "beliebige Farbe" (City of Brass, Treasure) zahlt es nicht
        if (c == Color.C) {
            return s.colors().contains(Color.C);
        }
        return s.any() || s.colors().contains(c);
    }

    /** kleiner = lieber zuerst verwenden */
    private static int compare(Source a, Source b, Cost cost) {
        int fa = a.flexibility() * 10 + (a.amount() > 1 && cost.generic() < a.amount() ? 5 : 0);
        int fb = b.flexibility() * 10 + (b.amount() > 1 && cost.generic() < b.amount() ? 5 : 0);
        return Integer.compare(fa, fb);
    }

    public static String colorName(Color c) {
        return switch (c) {
            case W -> "White";
            case U -> "Blue";
            case B -> "Black";
            case R -> "Red";
            case G -> "Green";
            case C -> "Colorless";
        };
    }
}
