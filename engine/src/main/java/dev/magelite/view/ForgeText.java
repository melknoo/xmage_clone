package dev.magelite.view;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge-Texte fuer die Anzeige: {@link #clean} glaettet (Forge haengt an Kartennamen die interne Objekt-Nummer
 * "Mountain (341)" und an Ausloeser den Kontext "... [Attacker: Jeleva (100)]"), {@link #german} uebersetzt die
 * haeufigen festen Forge-Saetze (Prompts, Dialoge, Aufdecken, Hinweise). Karten- und Regeltexte bleiben englisch;
 * Unbekanntes bleibt unveraendert.
 */
public final class ForgeText {

    /** " (341)" direkt hinter einem Namen; "Creature 4 / 7" oder "(Whenever ...)" bleiben */
    private static final Pattern OBJ_ID = Pattern.compile("(?<=[\\p{L}\\p{N}'’)])\\s\\(\\d{1,7}\\)(?=$|[\\s.,;:!?\\])])");
    /** Kontext-Klammern am Ende: " [Zone Changer: X]", " [Attacker: Y]" (auch mehrere) */
    private static final Pattern CONTEXT = Pattern.compile("(\\s*\\[[A-Z][A-Za-z ]{1,30}:[^\\[\\]]*\\])+\\s*$");

    private record Rule(Pattern p, String de) {
    }

    private static Rule r(String regex, String de) {
        return new Rule(Pattern.compile(regex), de);
    }

    /** Zonen: Akkusativ ("in …") und Dativ ("aus …"), eigene bzw. fremde */
    private static final Map<String, String[]> ZONES = Map.of(
            "library", new String[] {"deine Bibliothek", "die Bibliothek", "deiner Bibliothek", "der Bibliothek"},
            "hand", new String[] {"deine Hand", "die Hand", "deiner Hand", "der Hand"},
            "graveyard", new String[] {"deinen Friedhof", "den Friedhof", "deinem Friedhof", "dem Friedhof"},
            "battlefield", new String[] {"das Spielfeld", "das Spielfeld", "dem Spielfeld", "dem Spielfeld"},
            "exile", new String[] {"das Exil", "das Exil", "dem Exil", "dem Exil"});

    /**
     * Zeilenweise, erste passende Regel gewinnt. Platzhalter fuer Zonen aus Gruppe n: {AMn}/{AOn} Akkusativ eigene/fremde,
     * {DMn}/{DOn} Dativ eigene/fremde.
     */
    private static final List<Rule> RULES = List.of(
            // Fragen (Ja/Nein)
            r("^Use triggered ability of (.+?)\\?\\s*(.*)$", "Ausgelöste Fähigkeit von $1 nutzen? $2"),
            r("^Apply replacement effect of (.+?)\\?\\s*(.*)$", "Ersatzeffekt von $1 anwenden? $2"),
            r("^(.+?): If a commander is in a graveyard or in exile.*$", "$1 in die Kommandozone zurücklegen?"),
            r("^Move (.+?) to the command zone\\?$", "$1 in die Kommandozone legen?"),
            r("^Search your library\\?$", "Bibliothek durchsuchen?"),
            r("^Put (.+?) on the top or bottom of your library\\?$", "$1 oben oder unten in die Bibliothek legen?"),
            r("^Put (.+?) onto the battlefield\\?$", "$1 ins Spiel bringen?"),
            r("^Use (.+?) from your opening hand\\?$", "$1 aus der Starthand nutzen?"),
            r("^Sacrifice (.+?)\\?$", "$1 opfern?"),
            r("^Pay (\\d+) life\\?$", "$1 Leben zahlen?"),
            r("^Do you want to pay (\\d+) life\\?$", "$1 Leben zahlen?"),
            r("^Do you want to create a token that's a copy of that creature\\?$", "Token-Kopie dieser Kreatur erschaffen?"),
            r("^Do you want to pay (\\{.+\\})\\?$", "$1 zahlen?"),
            r("^Pay (\\{.+\\})\\?$", "$1 zahlen?"),
            // Zahlen, Marken, Auswahl
            r("^Choose X for (.+)$", "X für $1 wählen"),
            r("^Choose Amount for (.+?): (.+)$", "Anzahl für $1 wählen: $2"),
            r("^Put how many (.+?) counters on (.+?)\\?$", "Wie viele $1-Marken auf $2 legen?"),
            r("^Select Mana to Produce$", "Welches Mana erzeugen?"),
            r("^Choose optional costs$", "Optionale Kosten wählen"),
            r("^Choose a color$", "Farbe wählen"),
            r("^Choose a creature type$", "Kreaturentyp wählen"),
            r("^Choose a card name$", "Kartennamen wählen"),
            r("^Choose a player$", "Spieler wählen"),
            r("^Choose a pile$", "Stapel wählen"),
            r("^.+? (?:activated|cast) (.+?) - Choose a mode$", "$1 – Modus wählen"),
            r("^(.+?) - Choose a mode$", "$1 – Modus wählen"),
            r("^Choose a mode$", "Modus wählen"),
            r("^(?:Select order for|Reorder) simultaneous abilities – Resolve first \\((.+)\\)$",
                    "Reihenfolge gleichzeitiger Fähigkeiten – zuerst auflösen ($1)"),
            r("^(?:Select order for|Reorder) simultaneous abilities$", "Reihenfolge gleichzeitiger Fähigkeiten"),
            // Ziele und Karten waehlen
            r("^(.+?) – Select any target$", "$1 – beliebiges Ziel wählen"),
            r("^(.+?) – Select target (.+)$", "$1 – Ziel wählen: $2"),
            r("^Select a\\(n\\) (.+?) to tap \\((\\d+) left\\)$", "$1 zum Tappen wählen (noch $2)"),
            r("^Choose any number of permanents and/or players for proliferate$", "Bleibende Karten und/oder Spieler für Wuchern wählen"),
            r("^Choose cards to get (.+?) counters from (.+?)\\.?$", "Karten wählen, die $1-Marken von $2 bekommen"),
            r("^Cleanup Phase Select (\\d+) card\\(s\\) to discard to bring your hand down to the maximum of (\\d+) cards\\.$",
                    "Aufräumen: $1 Karte(n) abwerfen (Handlimit $2)"),
            r("^Select (\\d+) card\\(s\\) to discard.*$", "$1 Karte(n) abwerfen"),
            r("^Select a card from your (library|hand|graveyard|battlefield|exile)$", "Karte aus {DM1} wählen"),
            r("^Select a card to play$", "Karte zum Spielen wählen"),
            r("^Cleanup Phase$", "Aufräumen:"),
            r("^Select cards to be put on the bottom of your library$", "Karten wählen, die unter die Bibliothek kommen"),
            r("^Select cards to be put on (?:the )?top of your library$", "Karten wählen, die oben auf die Bibliothek kommen"),
            r("^Choose card\\(s\\) to put into exile$", "Karte(n) ins Exil legen"),
            r("^Choose card\\(s\\) to put into your hand$", "Karte(n) auf die Hand nehmen"),
            r("^Choose card\\(s\\) to put into your graveyard$", "Karte(n) in den Friedhof legen"),
            r("^Choose a card to put into your hand$", "Karte für die Hand wählen"),
            r("^Choose cards? to discard.*$", "Karte(n) zum Abwerfen wählen"),
            // Aufdecken / Ansehen (Titel der Kartenleisten)
            r("^Looking at cards in your (library|hand|graveyard)$", "Blick in {AM1}"),
            r("^Looking at cards in (.+?)'s (library|hand|graveyard)$", "Blick in {AO2} von $1"),
            r("^(.+?) - Revealing cards from (.+?)'s (library|hand|graveyard)$", "$1 – deckt Karten aus {DO3} von $2 auf"),
            r("^Revealing cards from (.+?)'s (library|hand|graveyard)$", "Aufgedeckt aus {DO2} von $1"),
            r("^Transformed cards in\\s+(.+?)'s battlefield$", "Verwandelt auf dem Spielfeld von $1"),
            // Hinweise
            r("^(.+?) wins the flip$", "$1 gewinnt den Münzwurf"),
            r("^(.+?) loses the flip$", "$1 verliert den Münzwurf"),
            r("^(.+?) picked (.+)$", "$1 wählt $2"),
            r("^(.+?) chose (.+)$", "$1 wählt $2"));

    private static final Pattern ZONE_MARK = Pattern.compile("\\{([AD])([MO])(\\d)\\}");

    private static final Map<String, String> BUTTONS = Map.of("Yes", "Ja", "No", "Nein", "Keep", "Behalten", "Cancel", "Abbrechen",
            "Top", "Oben", "Bottom", "Unten", "Done", "Fertig", "Skip", "Überspringen");

    private ForgeText() {
    }

    public static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = OBJ_ID.matcher(s).replaceAll("");
        out = CONTEXT.matcher(out).replaceAll("");
        return out;
    }

    /** Feste Forge-Saetze auf Deutsch (zeilenweise); Unbekanntes bleibt. Vorher {@link #clean}. */
    public static String german(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String[] lines = clean(s).split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(germanLine(lines[i]));
        }
        return out.toString();
    }

    private static String germanLine(String line) {
        String t = line.trim();
        for (Rule rule : RULES) {
            Matcher m = rule.p().matcher(t);
            if (!m.matches()) {
                continue;
            }
            // Zonen-Platzhalter zuerst ersetzen (brauchen die Gruppen), danach $n
            Matcher z = ZONE_MARK.matcher(rule.de());
            StringBuilder tpl = new StringBuilder();
            while (z.find()) {
                String[] forms = ZONES.get(m.group(Integer.parseInt(z.group(3))));
                int idx = ("A".equals(z.group(1)) ? 0 : 2) + ("M".equals(z.group(2)) ? 0 : 1);
                z.appendReplacement(tpl, Matcher.quoteReplacement(forms == null ? m.group(Integer.parseInt(z.group(3))) : forms[idx]));
            }
            z.appendTail(tpl);
            return m.replaceFirst(tpl.toString()).trim();
        }
        return line;
    }

    /** Knopfbeschriftung (Forges "Yes"/"No"/"Keep" …) */
    public static String button(String s) {
        return s == null ? null : BUTTONS.getOrDefault(s.trim(), s);
    }
}
