package dev.magelite.deck;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.CardType;
import mage.constants.SuperType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest Decklisten im Text-Format (Moxfield, Archidekt, MTGA, MTGO, XMage .dck, einfache Listen)
 * und loest die Karten ueber die XMage-Karten-DB auf.
 * <p>
 * Commander-Erkennung (in dieser Reihenfolge): explizite Angabe, Abschnitt "Commander",
 * Archidekt-Kategorie [Commander], Sideboard mit 1-2 Karten, sonst Kandidatenliste fuer die UI.
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

    public enum Section { MAIN, COMMANDER, SIDEBOARD, SIDE_GUESS, IGNORE }

    /** @param lineNo 1-basierte Zeile im Rohtext (Leer- und Kopfzeilen mitgezaehlt) */
    public record Entry(int count, String name, String set, String number, Section section, String category, String line, int lineNo) {
    }

    /**
     * Problemzeile fuer die Import-Vorschau. {@code kind}: unknown | unfinished. {@code suggestion} nur nach
     * {@link CardNameSuggester#withSuggestions} (nie beim Speichern).
     */
    public record Issue(int line, int count, String name, String suggestion, String kind) {
    }

    /** Hoechstzahl Zeilen fuer Vorschau/Speichern. */
    public static final int MAX_LINES = 600;

    public record Resolved(int count, String name, String set, String number, boolean commander) {
    }

    public record Result(
            String name,
            List<Resolved> main,
            List<Resolved> commanders,
            List<String> unknown,
            List<String> unfinished,
            boolean needsCommander,
            List<String> candidates,
            int cardCount,
            List<Issue> issues,
            Map<String, String> types
    ) {
        /** Grobe Kartenart fuer die Vorschau-Gruppen (siehe {@link #kindOf}); null = unbekannt. */
        public String typeOf(String cardName) {
            return types.get(cardName);
        }

        public DeckCardLists toLists() {
            DeckCardLists l = new DeckCardLists();
            l.setName(name);
            List<DeckCardInfo> cards = new ArrayList<>();
            for (Resolved r : main) {
                cards.add(new DeckCardInfo(r.name(), r.number(), r.set(), r.count()));
            }
            List<DeckCardInfo> side = new ArrayList<>();
            for (Resolved r : commanders) {
                side.add(new DeckCardInfo(r.name(), r.number(), r.set(), r.count()));
            }
            l.setCards(cards);
            l.setSideboard(side);
            return l;
        }

        /** XMage-.dck-Text zum Speichern. */
        public String toDck() {
            StringBuilder sb = new StringBuilder();
            sb.append("NAME:").append(name).append('\n');
            for (Resolved r : main) {
                sb.append(r.count()).append(" [").append(r.set()).append(':').append(r.number()).append("] ").append(r.name()).append('\n');
            }
            for (Resolved r : commanders) {
                sb.append("SB: ").append(r.count()).append(" [").append(r.set()).append(':').append(r.number()).append("] ").append(r.name()).append('\n');
            }
            return sb.toString();
        }
    }

    private TextDeckParser() {
    }

    public static List<Entry> parseLines(String text) {
        List<Entry> out = new ArrayList<>();
        Section section = Section.MAIN;
        boolean sawMainHeader = false;
        int blankAfterCards = 0;
        int lineNo = 0;
        for (String raw : text.split("\\r?\\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty()) {
                if (!out.isEmpty()) {
                    blankAfterCards++;
                }
                continue;
            }
            if (line.startsWith("#") || line.startsWith("NAME:") || line.startsWith("AUTHOR:") || line.startsWith("LAYOUT ")) {
                continue;
            }
            String header = line.replaceFirst("^//\\s*", "").replaceFirst(":$", "").replaceAll("\\s*\\(\\d+\\)$", "").toLowerCase(Locale.ROOT).trim();
            Section h = headerSection(header);
            if (h != null && !Character.isDigit(line.charAt(0))) {
                section = h;
                if (h == Section.MAIN) {
                    sawMainHeader = true;
                }
                continue;
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
        return out;
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
            case "maybeboard", "maybe", "considering", "tokens", "token", "companion", "attractions", "stickers" -> {
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
        List<Entry> entries = parseLines(text);
        Map<String, Resolved> main = new LinkedHashMap<>();
        Map<String, Resolved> cmd = new LinkedHashMap<>();
        Map<String, Resolved> side = new LinkedHashMap<>();
        Map<String, Resolved> guess = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        List<String> unfinished = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        Map<String, String> types = new LinkedHashMap<>();

        for (Entry e : entries) {
            if (e.section() == Section.IGNORE) {
                continue;
            }
            CardInfo info = resolve(e.name(), e.set(), e.number());
            if (info == null) {
                String n = e.name();
                boolean wip = XmageUnfinished.contains(n) || (n.contains("/") && XmageUnfinished.contains(frontFace(n)));
                (wip ? unfinished : unknown).add(e.count() + " " + n);
                issues.add(new Issue(e.lineNo(), e.count(), n, null, wip ? "unfinished" : "unknown"));
                continue;
            }
            types.putIfAbsent(info.getName(), kindOf(info));
            Map<String, Resolved> target = switch (e.section()) {
                case COMMANDER -> cmd;
                case SIDEBOARD -> side;
                case SIDE_GUESS -> guess;
                default -> main;
            };
            target.merge(info.getName(), new Resolved(e.count(), info.getName(), info.getSetCode(), info.getCardNumber(), e.section() == Section.COMMANDER),
                    (a, b) -> new Resolved(a.count() + b.count(), a.name(), a.set(), a.number(), a.commander()));
        }

        // Leerzeilen-Gruppe: 1-2 Einzelkarten = Commander/Sideboard, sonst gehoert sie zum Deck
        if (!guess.isEmpty()) {
            boolean looksLikeSide = guess.size() <= 2 && guess.values().stream().allMatch(r -> r.count() == 1);
            Map<String, Resolved> into = looksLikeSide ? side : main;
            for (Resolved r : guess.values()) {
                into.merge(r.name(), r, (a, b) -> new Resolved(a.count() + b.count(), a.name(), a.set(), a.number(), false));
            }
        }

        // Commander bestimmen
        if (forcedCommanders != null && !forcedCommanders.isEmpty()) {
            for (Resolved r : side.values()) {
                main.merge(r.name(), r, (a, b) -> new Resolved(a.count() + b.count(), a.name(), a.set(), a.number(), false));
            }
            side.clear();
            for (Resolved r : cmd.values()) {
                main.merge(r.name(), r, (a, b) -> new Resolved(a.count() + b.count(), a.name(), a.set(), a.number(), false));
            }
            cmd.clear();
            for (String c : forcedCommanders) {
                Resolved r = findByName(main, c);
                if (r == null) {
                    CardInfo info = resolve(c, null, null);
                    if (info != null) {
                        r = new Resolved(1, info.getName(), info.getSetCode(), info.getCardNumber(), true);
                        types.putIfAbsent(info.getName(), kindOf(info));
                    }
                } else {
                    take(main, r.name());
                }
                if (r != null) {
                    cmd.put(r.name(), new Resolved(1, r.name(), r.set(), r.number(), true));
                }
            }
        } else if (cmd.isEmpty() && !side.isEmpty() && side.size() <= 2 && side.values().stream().allMatch(r -> r.count() == 1)) {
            cmd.putAll(side);
            side.clear();
        }
        // restliches Sideboard gehoert im Commander nicht ins Deck (Companion etc. ignorieren)
        List<String> candidates = new ArrayList<>();
        boolean needsCommander = cmd.isEmpty();
        if (needsCommander) {
            for (Resolved r : main.values()) {
                if (canBeCommander(r)) {
                    candidates.add(r.name());
                }
            }
        }
        int count = main.values().stream().mapToInt(Resolved::count).sum() + cmd.values().stream().mapToInt(Resolved::count).sum();
        String name = deckName != null && !deckName.isBlank() ? deckName.strip()
                : cmd.isEmpty() ? "Neues Deck" : cmd.keySet().iterator().next().split(",")[0];
        return new Result(name, new ArrayList<>(main.values()), new ArrayList<>(cmd.values()), unknown, unfinished, needsCommander, candidates, count,
                issues, types);
    }

    /** creature &gt; land &gt; planeswalker &gt; battle &gt; instant &gt; sorcery &gt; artifact &gt; enchantment &gt; other */
    public static String kindOf(CardInfo info) {
        List<CardType> t = info.getTypes();
        if (t.contains(CardType.CREATURE)) return "creature";
        if (t.contains(CardType.LAND)) return "land";
        if (t.contains(CardType.PLANESWALKER)) return "planeswalker";
        if (t.contains(CardType.BATTLE)) return "battle";
        if (t.contains(CardType.INSTANT)) return "instant";
        if (t.contains(CardType.SORCERY)) return "sorcery";
        if (t.contains(CardType.ARTIFACT)) return "artifact";
        if (t.contains(CardType.ENCHANTMENT)) return "enchantment";
        return "other";
    }

    /** Wirft bei mehr als {@link #MAX_LINES} Zeilen. */
    public static void checkSize(String text) {
        if (text != null && text.split("\\r?\\n", -1).length > MAX_LINES) {
            throw new IllegalArgumentException("Die Liste ist zu lang (höchstens " + MAX_LINES + " Zeilen).");
        }
    }

    private static Resolved findByName(Map<String, Resolved> map, String name) {
        for (Resolved r : map.values()) {
            if (r.name().equalsIgnoreCase(name) || frontFace(r.name()).equalsIgnoreCase(frontFace(name))) {
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
            map.put(name, new Resolved(r.count() - 1, r.name(), r.set(), r.number(), false));
        }
    }

    static CardInfo resolve(String name, String set, String number) {
        CardRepository repo = CardRepository.instance;
        String n = name.replace('’', '\'').trim();
        CardInfo info = null;
        if (set != null) {
            String s = set.toUpperCase(Locale.ROOT);
            info = repo.findCardWithPreferredSetAndNumber(n, s, number);
            if (info == null && n.contains(" // ")) {
                info = repo.findCardWithPreferredSetAndNumber(frontFace(n), s, number);
            }
        }
        if (info == null) {
            info = repo.findPreferredCoreExpansionCard(n);
        }
        if (info == null && n.contains("/")) {
            info = repo.findPreferredCoreExpansionCard(frontFace(n));
        }
        if (info == null && !n.contains(" // ") && n.contains("/")) {
            info = repo.findPreferredCoreExpansionCard(n.replaceAll("\\s*/+\\s*", " // "));
        }
        return info;
    }

    private static String frontFace(String name) {
        int i = name.indexOf('/');
        return i > 0 ? name.substring(0, i).trim() : name;
    }

    private static boolean canBeCommander(Resolved r) {
        CardInfo info = CardRepository.instance.findCardWithPreferredSetAndNumber(r.name(), r.set(), r.number());
        if (info == null) {
            return false;
        }
        boolean legendary = info.getSupertypes().contains(SuperType.LEGENDARY);
        boolean creature = info.getTypes().contains(CardType.CREATURE);
        boolean textAllows = info.getRules().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains("can be your commander"));
        return (legendary && creature) || textAllows;
    }
}
