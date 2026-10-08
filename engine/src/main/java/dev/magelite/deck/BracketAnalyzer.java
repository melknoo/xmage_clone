package dev.magelite.deck;

import mage.cards.repository.CardInfo;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Vorschlag fuer die Commander-Bracket (WotC 1-5) aus der Deckliste. Grundlage wie XMages Bracket-Anzeige: Game Changers
 * ({@code /brackets/game-changers.txt}), fruehe 2-Karten-Combos ({@code /brackets/infinite-combos.txt} aus dem
 * XMage-Jar), Massen-Landzerstoerung und Extra-Zuege per Regeltext; dazu Tutoren. Brackets 1 und 5 sind Absicht
 * (Thema bzw. cEDH) und werden nie vorgeschlagen.
 * <ul>
 *   <li>4: Massen-Landzerstoerung, 2-Karten-Combo oder mehr als 3 Game Changers</li>
 *   <li>3: 1-3 Game Changers, Extra-Zuege oder ab {@value #MANY_TUTORS} Tutoren</li>
 *   <li>2: sonst</li>
 * </ul>
 */
public final class BracketAnalyzer {

    private static final Logger LOG = Logger.getLogger(BracketAnalyzer.class);
    static final int MANY_TUTORS = 4;

    /** Ein Grund fuer den Vorschlag: Art (gameChanger | combo | mld | extraTurn | tutor) und betroffene Karten. */
    public record Reason(String kind, List<String> cards) {
    }

    public record Result(int bracket, List<Reason> reasons) {
    }

    private static final Set<String> GAME_CHANGERS = loadNames("/brackets/game-changers.txt");
    /** Paare "a@b" (Vorderseiten, klein) */
    private static final List<String[]> COMBOS = loadCombos();

    private static final Pattern MLD = Pattern.compile(
            "(destroy|sacrifice)s? all (?:[a-z]+ )?(lands|plains|islands|swamps|mountains|forests)"
                    + "|destroy x target lands|each player sacrifices (?:all|x) lands");
    private static final Pattern EXTRA_TURN = Pattern.compile(
            "(?<!opponent )(?<!its controller )takes? an extra turn|take (?:two|three|x) extra turns");
    private static final Pattern TUTOR = Pattern.compile("search(?:es)? (?:your|their) library for (?:a|an|up to [a-z]+|any number of) (?:[a-z, ]+)?card");

    /** Suche nach Laendern (Rampe, Fetch) ist kein Tutor */
    private static final Pattern LAND_SEARCH = Pattern.compile("\\b(?:lands?|basic|plains|islands?|swamps?|mountains?|forests?)\\b");

    private BracketAnalyzer() {
    }

    /** Karten des Decks (Commander + Hauptdeck, je Name einmal). Unbekannte Karten werden uebersprungen. */
    public static Result analyze(List<TextDeckParser.Resolved> cards) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> gc = new TreeSet<>();
        Set<String> mld = new TreeSet<>();
        Set<String> extra = new TreeSet<>();
        Set<String> tutors = new TreeSet<>();
        for (TextDeckParser.Resolved r : cards) {
            if (!names.add(r.name())) {
                continue;
            }
            if (GAME_CHANGERS.contains(front(r.name()))) {
                gc.add(r.name());
            }
            CardInfo info;
            try {
                info = TextDeckParser.resolve(r.name(), r.set(), r.number());
            } catch (RuntimeException e) {
                info = null;
            }
            if (info == null) {
                continue;
            }
            String text = String.join(" ", info.getRules()).toLowerCase(Locale.ROOT);
            if (MLD.matcher(text).find()) {
                mld.add(r.name());
            }
            if (EXTRA_TURN.matcher(text).find()) {
                extra.add(r.name());
            }
            if (isTutor(text)) {
                tutors.add(r.name());
            }
        }
        Set<String> fronts = new HashSet<>();
        names.forEach(n -> fronts.add(front(n)));
        Set<String> combos = new TreeSet<>();
        for (String[] c : COMBOS) {
            if (fronts.contains(c[0]) && fronts.contains(c[1])) {
                combos.add(display(names, c[0]) + " + " + display(names, c[1]));
            }
        }

        List<Reason> reasons = new ArrayList<>();
        add(reasons, "gameChanger", gc);
        add(reasons, "combo", combos);
        add(reasons, "mld", mld);
        add(reasons, "extraTurn", extra);
        add(reasons, "tutor", tutors);
        int bracket;
        if (!mld.isEmpty() || !combos.isEmpty() || gc.size() > 3) {
            bracket = 4;
        } else if (!gc.isEmpty() || !extra.isEmpty() || tutors.size() >= MANY_TUTORS) {
            bracket = 3;
        } else {
            bracket = 2;
        }
        return new Result(bracket, reasons);
    }

    /** Tutor = sucht eine Nicht-Land-Karte (Rampe wie "basic land card" oder "forest card" zaehlt nicht). */
    static boolean isTutor(String text) {
        var m = TUTOR.matcher(text);
        while (m.find()) {
            String hit = m.group();
            if (!LAND_SEARCH.matcher(hit).find()) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Reason> out, String kind, Set<String> cards) {
        if (!cards.isEmpty()) {
            out.add(new Reason(kind, List.copyOf(cards)));
        }
    }

    private static String display(Set<String> names, String front) {
        for (String n : names) {
            if (front(n).equals(front)) {
                return n;
            }
        }
        return front;
    }

    /** Vorderseite in Kleinbuchstaben ("A // B" -> "a") */
    static String front(String name) {
        int i = name.indexOf(" // ");
        return (i < 0 ? name : name.substring(0, i)).trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> loadNames(String resource) {
        Set<String> out = new HashSet<>();
        for (String line : lines(resource)) {
            out.add(front(line));
        }
        return out;
    }

    private static List<String[]> loadCombos() {
        List<String[]> out = new ArrayList<>();
        for (String line : lines("/brackets/infinite-combos.txt")) {
            String[] p = line.split("@");
            if (p.length == 2) {
                out.add(new String[]{front(p[0]), front(p[1])});
            }
        }
        return out;
    }

    private static List<String> lines(String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream in = BracketAnalyzer.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warn("Bracket-Liste fehlt: " + resource);
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    out.add(line);
                }
            }
        } catch (Exception e) {
            LOG.warn("Bracket-Liste nicht lesbar: " + resource, e);
        }
        return out;
    }
}
