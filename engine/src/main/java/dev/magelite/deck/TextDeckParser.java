package dev.magelite.deck;

import com.fasterxml.jackson.annotation.JsonIgnore;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest Decklisten im Textformat und loest die Karten ueber {@link CardLookup} (Forge) auf. Verstanden werden:
 * MageLite-Decktext v2 ({@code 1 Name (SET) NUM}, Abschnitte {@code Commander}/{@code Deck}), Moxfield, Archidekt, MTGA,
 * MTGO, einfache Listen, Forge-{@code .dck} ({@code [Commander]}/{@code [Main]}, {@code N Name|SET|art}) und das alte
 * XMage-{@code .dck} ({@code N [SET:num] Name}, Commander als {@code SB:}).
 * <p>
 * Commander-Erkennung (in dieser Reihenfolge): explizite Angabe, Abschnitt "Commander", Archidekt-Kategorie [Commander],
 * Sideboard mit 1-2 Karten, sonst Kandidatenliste fuer die UI.
 * <p>
 * Gespeichert wird immer {@link Result#toText()} (v2, Scryfall-Set + Nummer), nie Forge-{@code .dck}.
 */
public final class TextDeckParser {

    /** 1x Name (SET) 123 *F* [Kategorie] */
    private static final Pattern CARD = Pattern.compile(
            "^(?:(\\d+)\\s*[xX]?\\s+)?(.+?)"
                    + "(?:\\s+[(\\[]([A-Za-z0-9_]{2,8})[)\\]](?:\\s+([A-Za-z0-9★†Φ*+\\-/]+))?)?"
                    + "((?:\\s+\\*[A-Za-z]+\\*)*)"
                    + "(?:\\s+\\[([^\\]]*)])?"
                    + "(?:\\s+\\^[^^]*\\^)?\\s*$");
    /** XMage .dck: 1 [SET:123] Name */
    private static final Pattern DCK = Pattern.compile("^(\\d+)\\s*\\[([^]:]+):([^]]+)]\\s*(.+?)\\s*$");
    /** Forge .dck: 3 Name|SET|art (Set und Art optional, Rest hinter weiteren '|' wird ignoriert) */
    private static final Pattern FORGE = Pattern.compile("^(?:(\\d+)\\s*[xX]?\\s+)?([^|]+?)\\s*\\|([^|]*)(?:\\|([^|]*))?(?:\\|.*)?$");
    private static final Pattern BRACKET_HEADER = Pattern.compile("^\\[([^\\]]+)]$");

    public enum Section { MAIN, COMMANDER, SIDEBOARD, SIDE_GUESS, IGNORE }

    /**
     * @param lineNo 1-basierte Zeile im Rohtext (Leer- und Kopfzeilen mitgezaehlt)
     * @param art    Forge-Art-Index aus {@code Name|SET|2} oder 0
     */
    public record Entry(int count, String name, String set, String number, Section section, String category, String line,
                        int lineNo, int art) {
        public Entry(int count, String name, String set, String number, Section section, String category, String line, int lineNo) {
            this(count, name, set, number, section, category, line, lineNo, 0);
        }
    }

    /**
     * Problemzeile fuer die Import-Vorschau. {@code kind}: nur noch {@code unknown}. {@code suggestion} nur nach
     * {@link CardNameSuggester#withSuggestions} (nie beim Speichern).
     */
    public record Issue(int line, int count, String name, String suggestion, String kind) {
    }

    /** Hoechstzahl Zeilen fuer Vorschau/Speichern. */
    public static final int MAX_LINES = 600;

    /**
     * Aufgeloeste Karte. {@code set} = Scryfall-Code in Grossbuchstaben (oder ""), {@code number} = Sammlernummer (oder "");
     * {@code card} = Forge-Druck (nicht in JSON, {@code @JsonIgnore}; kann bei Hilfs-Konstruktoren null sein).
     */
    public record Resolved(int count, String name, String set, String number, boolean commander, @JsonIgnore PaperCard card) {
        public Resolved(int count, String name, String set, String number, boolean commander) {
            this(count, name, set, number, commander, null);
        }

        Resolved withCount(int c) {
            return new Resolved(c, name, set, number, commander, card);
        }

        Resolved asMain() {
            return new Resolved(count, name, set, number, false, card);
        }

        Resolved asCommander() {
            return new Resolved(1, name, set, number, true, card);
        }

        /** Forge-Druck (aufgeloest, falls beim Parsen nicht schon vorhanden). */
        public PaperCard printing() {
            return card != null ? card : CardLookup.resolve(name, set, number);
        }

        String line() {
            StringBuilder sb = new StringBuilder().append(count).append(' ').append(name);
            if (set != null && !set.isEmpty()) {
                sb.append(" (").append(set).append(')');
                if (number != null && !number.isEmpty()) {
                    sb.append(' ').append(number);
                }
            }
            return sb.toString();
        }
    }

    public record Result(
            String name,
            List<Resolved> main,
            List<Resolved> commanders,
            List<String> unknown,
            boolean needsCommander,
            List<String> candidates,
            int cardCount,
            List<Issue> issues,
            Map<String, String> types
    ) {
        /** Grobe Kartenart fuer die Vorschau-Gruppen (siehe {@link CardLookup#kindOf}); null = unbekannt. */
        public String typeOf(String cardName) {
            return types.get(cardName);
        }

        /** Mit anderen Issues (Vorschlaege der {@link CardNameSuggester}), sonst unveraendert. */
        public Result withIssues(List<Issue> issues) {
            return new Result(name, main, commanders, unknown, needsCommander, candidates, cardCount, issues, types);
        }

        /**
         * MageLite-Decktext v2 zum Speichern: {@code Commander} zuerst, dann {@code Deck} nach Name, je Zeile
         * {@code N Name (SET) NUM}. Unbekannte Karten fehlen.
         */
        public String toText() {
            return toText(false);
        }

        /** Wie {@link #toText()}; mit {@code withName} beginnt der Text mit {@code NAME:<name>} (nur Sample-Dateien). */
        public String toText(boolean withName) {
            StringBuilder sb = new StringBuilder();
            if (withName && name != null && !name.isBlank()) {
                sb.append("NAME:").append(name.replace('\n', ' ').strip()).append('\n');
            }
            sb.append("Commander\n");
            for (Resolved r : commanders) {
                sb.append(r.line()).append('\n');
            }
            sb.append("Deck\n");
            List<Resolved> sorted = new ArrayList<>(main);
            sorted.sort(Comparator.comparing(Resolved::name, String.CASE_INSENSITIVE_ORDER));
            for (Resolved r : sorted) {
                sb.append(r.line()).append('\n');
            }
            return sb.toString();
        }
    }

    private TextDeckParser() {
    }

    /** Zerlegter Rohtext: Karten-Zeilen und im Text hinterlegter Deckname ({@code NAME:} bzw. {@code [metadata] Name=}). */
    private record Scanned(List<Entry> entries, String declaredName) {
    }

    public static List<Entry> parseLines(String text) {
        return scan(text).entries();
    }

    /** Im Text hinterlegter Deckname ({@code NAME:} / Forge {@code Name=}) oder null. */
    public static String declaredName(String text) {
        return scan(text).declaredName();
    }

    private static Scanned scan(String text) {
        List<Entry> out = new ArrayList<>();
        String declared = null;
        Section section = Section.MAIN;
        boolean sawMainHeader = false;
        boolean inMeta = false;
        int blankAfterCards = 0;
        int lineNo = 0;
        for (String raw : text.split("\\r?\\n")) {
            lineNo++;
            String line = raw.strip();
            if (!line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1).strip();
            }
            if (line.isEmpty()) {
                if (!out.isEmpty()) {
                    blankAfterCards++;
                }
                continue;
            }
            if (line.startsWith("NAME:")) {
                String n = line.substring(5).strip();
                if (declared == null && !n.isEmpty()) {
                    declared = n;
                }
                continue;
            }
            if (line.startsWith("#") || line.startsWith("AUTHOR:") || line.startsWith("LAYOUT ")) {
                continue;
            }
            if (inMeta && !line.startsWith("[")) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).strip().equalsIgnoreCase("name") && declared == null) {
                    String n = line.substring(eq + 1).strip();
                    declared = n.isEmpty() ? null : n;
                }
                continue;
            }
            String header = line.replaceFirst("^//\\s*", "").replaceFirst(":$", "").replaceAll("\\s*\\(\\d+\\)$", "")
                    .replaceFirst("^\\[(.*)]$", "$1").toLowerCase(Locale.ROOT).trim();
            if (!Character.isDigit(line.charAt(0))) {
                if (header.equals("metadata")) {
                    inMeta = true;
                    continue;
                }
                Section h = headerSection(header);
                if (h == null && BRACKET_HEADER.matcher(line).matches()) {
                    h = Section.IGNORE; // unbekannter Forge-Abschnitt ([Planes], [Schemes], ...)
                }
                if (h != null) {
                    inMeta = false;
                    section = h;
                    if (h == Section.MAIN) {
                        sawMainHeader = true;
                    }
                    continue;
                }
            }
            Section lineSection = section;
            if (line.startsWith("SB:")) {
                line = line.substring(3).strip();
                lineSection = Section.SIDEBOARD;
            } else if (!sawMainHeader && section == Section.MAIN && blankAfterCards > 0) {
                // MTGO/Moxfield: Liste, Leerzeile, dann Sideboard/Commander (wird spaeter geprueft)
                lineSection = Section.SIDE_GUESS;
            }
            Matcher d = DCK.matcher(line);
            if (d.matches()) {
                out.add(new Entry(Integer.parseInt(d.group(1)), d.group(4).trim(), d.group(2), d.group(3), lineSection, null, raw, lineNo));
                continue;
            }
            if (line.indexOf('|') > 0) {
                Matcher f = FORGE.matcher(line);
                if (f.matches()) {
                    out.add(forgeEntry(f, lineSection, raw, lineNo));
                    continue;
                }
            }
            Matcher m = CARD.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int count = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            String name = m.group(2).trim();
            String category = m.group(6);
            Section s = lineSection;
            if (category != null) {
                String cat = category.toLowerCase(Locale.ROOT);
                if (cat.startsWith("commander")) {
                    s = Section.COMMANDER;
                } else if (cat.contains("maybeboard") || cat.contains("sideboard") || cat.contains("considering")) {
                    s = Section.IGNORE;
                }
            }
            out.add(new Entry(count, name, m.group(3), m.group(4), s, category, raw, lineNo));
        }
        return new Scanned(out, declared);
    }

    /** {@code 3 Name|SET|art}: Set (Forge-Code), Art-Index oder {@code [Nummer]}; Foil-"+" am Namen faellt weg. */
    private static Entry forgeEntry(Matcher f, Section section, String raw, int lineNo) {
        int count = f.group(1) == null ? 1 : Integer.parseInt(f.group(1));
        String name = f.group(2).trim();
        if (name.endsWith("+")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        String set = f.group(3) == null || f.group(3).isBlank() ? null : f.group(3).trim();
        String third = f.group(4) == null ? "" : f.group(4).trim();
        String number = null;
        int art = 0;
        if (third.startsWith("[") && third.endsWith("]")) {
            number = third.substring(1, third.length() - 1).trim();
        } else if (third.matches("\\d{1,2}")) {
            art = Integer.parseInt(third);
        }
        return new Entry(count, name, set, number == null || number.isEmpty() ? null : number, section, null, raw, lineNo, art);
    }

    private static Section headerSection(String h) {
        switch (h) {
            case "commander", "commanders", "command zone", "kommandant", "partner" -> {
                return Section.COMMANDER;
            }
            case "deck", "main", "mainboard", "main deck", "maindeck", "library", "creatures", "lands", "spells" -> {
                return Section.MAIN;
            }
            case "sideboard", "side", "sb" -> {
                return Section.SIDEBOARD;
            }
            case "maybeboard", "maybe", "considering", "tokens", "token", "companion", "attractions", "stickers",
                 "planes", "schemes", "avatar", "conspiracy", "dungeon", "dungeons", "contraptions" -> {
                return Section.IGNORE;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * @param forcedCommanders vom Nutzer gewaehlte Commander (Namen) oder null
     */
    public static Result parse(String text, String deckName, List<String> forcedCommanders) {
        Scanned scanned = scan(text);
        List<Entry> entries = scanned.entries();
        Map<String, Resolved> main = new LinkedHashMap<>();
        Map<String, Resolved> cmd = new LinkedHashMap<>();
        Map<String, Resolved> side = new LinkedHashMap<>();
        Map<String, Resolved> guess = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Map<String, String> types = new LinkedHashMap<>();

        for (Entry e : entries) {
            if (e.section() == Section.IGNORE) {
                continue;
            }
            PaperCard pc = CardLookup.resolve(e.name(), e.set(), e.number(), e.art());
            if (pc == null) {
                unknown.add(e.count() + " " + e.name());
                issues.add(new Issue(e.lineNo(), e.count(), e.name(), null, "unknown"));
                continue;
            }
            types.putIfAbsent(pc.getName(), CardLookup.kindOf(pc));
            Map<String, Resolved> target = switch (e.section()) {
                case COMMANDER -> cmd;
                case SIDEBOARD -> side;
                case SIDE_GUESS -> guess;
                default -> main;
            };
            target.merge(pc.getName(), resolved(pc, e.count(), e.section() == Section.COMMANDER), TextDeckParser::add);
        }

        // Leerzeilen-Gruppe: 1-2 Einzelkarten = Commander/Sideboard, sonst gehoert sie zum Deck
        if (!guess.isEmpty()) {
            boolean looksLikeSide = guess.size() <= 2 && guess.values().stream().allMatch(r -> r.count() == 1);
            Map<String, Resolved> into = looksLikeSide ? side : main;
            for (Resolved r : guess.values()) {
                into.merge(r.name(), r.asMain(), TextDeckParser::add);
            }
        }

        // Commander bestimmen
        if (forcedCommanders != null && !forcedCommanders.isEmpty()) {
            for (Resolved r : side.values()) {
                main.merge(r.name(), r.asMain(), TextDeckParser::add);
            }
            side.clear();
            for (Resolved r : cmd.values()) {
                main.merge(r.name(), r.asMain(), TextDeckParser::add);
            }
            cmd.clear();
            for (String c : forcedCommanders) {
                Resolved r = findByName(main, c);
                if (r == null) {
                    PaperCard pc = CardLookup.resolve(c, null, null);
                    if (pc != null) {
                        r = resolved(pc, 1, true);
                        types.putIfAbsent(pc.getName(), CardLookup.kindOf(pc));
                    }
                } else {
                    take(main, r.name());
                }
                if (r != null) {
                    cmd.put(r.name(), r.asCommander());
                }
            }
        } else if (cmd.isEmpty() && !side.isEmpty() && side.size() <= 2 && side.values().stream().allMatch(r -> r.count() == 1)) {
            for (Resolved r : side.values()) {
                cmd.put(r.name(), r.asCommander());
            }
            side.clear();
        }
        // restliches Sideboard gehoert im Commander nicht ins Deck (Companion etc. ignorieren)
        List<String> candidates = new ArrayList<>();
        boolean needsCommander = cmd.isEmpty();
        if (needsCommander) {
            for (Resolved r : main.values()) {
                if (CardLookup.canBeCommander(r.printing())) {
                    candidates.add(r.name());
                }
            }
        }
        int count = main.values().stream().mapToInt(Resolved::count).sum() + cmd.values().stream().mapToInt(Resolved::count).sum();
        String name = deckName != null && !deckName.isBlank() ? deckName.strip()
                : scanned.declaredName() != null ? scanned.declaredName()
                : cmd.isEmpty() ? "Neues Deck" : cmd.keySet().iterator().next().split(",")[0];
        return new Result(name, new ArrayList<>(main.values()), new ArrayList<>(cmd.values()), unknown, needsCommander, candidates, count,
                issues, types);
    }

    private static Resolved resolved(PaperCard pc, int count, boolean commander) {
        return new Resolved(count, pc.getName(), CardLookup.scryfallSet(pc), CardLookup.number(pc), commander, pc);
    }

    private static Resolved add(Resolved a, Resolved b) {
        return a.withCount(a.count() + b.count());
    }

    /** Wirft bei mehr als {@link #MAX_LINES} Zeilen. */
    public static void checkSize(String text) {
        if (text != null && text.split("\\r?\\n", -1).length > MAX_LINES) {
            throw new IllegalArgumentException("Die Liste ist zu lang (höchstens " + MAX_LINES + " Zeilen).");
        }
    }

    private static Resolved findByName(Map<String, Resolved> map, String name) {
        String wanted = CardLookup.normalizeName(name);
        for (Resolved r : map.values()) {
            if (r.name().equalsIgnoreCase(wanted) || CardLookup.frontFace(r.name()).equalsIgnoreCase(CardLookup.frontFace(wanted))) {
                return r;
            }
        }
        return null;
    }

    private static void take(Map<String, Resolved> map, String name) {
        Resolved r = map.get(name);
        if (r == null) {
            return;
        }
        if (r.count() <= 1) {
            map.remove(name);
        } else {
            map.put(name, r.withCount(r.count() - 1).asMain());
        }
    }
}
