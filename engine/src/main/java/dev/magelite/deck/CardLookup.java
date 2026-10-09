package dev.magelite.deck;

import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.card.ICardFace;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Einzige Deck-Klasse mit Forge-Kartenzugriff: loest Name (+ Set + Nummer) auf {@link PaperCard} auf und liefert die
 * Kennzahlen, die der Rest des Deck-Layers braucht (Scryfall-Set, Nummer, Kartenart, Farben, Regeltext).
 * <p>
 * Forges Set-Codes weichen von Scryfalls ab (z. B. Conflux: Forge {@code CFX}, Scryfall {@code CON}); gespeichert und
 * uebertragen wird immer der <b>Scryfall-Code</b> in Grossbuchstaben. Beim Aufloesen gilt jeder Code, den Forge oder
 * Scryfall fuer ein Set kennt (eigener Index, {@code CardEdition.Collection.get} kennt keine Scryfall-Codes).
 * Braucht gebootetes Forge ({@code ForgeBoot.init}).
 */
public final class CardLookup {

    private static final Pattern SPLIT_SEP = Pattern.compile("\\s*/{1,2}\\s*");

    private CardLookup() {
    }

    /** Set-Code (beliebiger Art, GROSS) -&gt; Editionen, Scryfall-Code zuerst. */
    private static final class Holder {
        static final Map<String, List<CardEdition>> BY_CODE = buildIndex();
        static volatile List<String> names;

        private static Map<String, List<CardEdition>> buildIndex() {
            Map<String, List<CardEdition>> m = new LinkedHashMap<>();
            Iterable<CardEdition> all = StaticData.instance().getEditions();
            // 1. Scryfall-Codes, 2. Forge-Code/Code2/Alias (nur wenn der Schluessel noch keine Edition hat oder sie noch fehlt)
            for (CardEdition e : all) {
                put(m, e.getScryfallCode(), e);
            }
            for (CardEdition e : all) {
                put(m, e.getCode(), e);
                put(m, e.getCode2(), e);
                put(m, e.getAlias(), e);
            }
            return m;
        }

        private static void put(Map<String, List<CardEdition>> m, String code, CardEdition e) {
            if (code == null || code.isBlank()) {
                return;
            }
            List<CardEdition> l = m.computeIfAbsent(code.trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>(1));
            if (!l.contains(e)) {
                l.add(e);
            }
        }
    }

    private static CardDb db() {
        return StaticData.instance().getCommonCards();
    }

    // ---------------------------------------------------------------------------------------------------------- Aufloesen

    /** Karte per Name; {@code set}/{@code num} (Scryfall- oder Forge-Code, Nummer) sind optional. null = unbekannt. */
    public static PaperCard resolve(String name, String set, String num) {
        return resolve(name, set, num, 0);
    }

    /**
     * @param art Forge-Art-Index (1..n, aus {@code Name|SET|2}) oder 0 = egal
     */
    public static PaperCard resolve(String name, String set, String num, int art) {
        if (name == null) {
            return null;
        }
        String n = normalizeName(name);
        if (n.isEmpty()) {
            return null;
        }
        CardDb db = db();
        List<String> candidates = nameCandidates(n);
        List<PaperCard> prints = List.of();
        // Erst exakte Hauptnamen (Split-Karten "Fire // Ice"), dann Nebennamen (Rueckseite, Abenteuer, Flavor-Name)
        for (String c : candidates) {
            List<PaperCard> p = db.getAllCardsNoAlt(c);
            if (!p.isEmpty()) {
                prints = p;
                break;
            }
        }
        if (prints.isEmpty()) {
            for (String c : candidates) {
                List<PaperCard> p = db.getAllCards(c);
                if (!p.isEmpty()) {
                    prints = p;
                    break;
                }
            }
        }
        if (prints.isEmpty()) {
            return null;
        }
        PaperCard hit = pickPrint(prints, set, num, art);
        if (hit != null) {
            return hit;
        }
        // nur Name (oder Set/Nummer unbekannt): Forges Art-Praeferenz (neuestes Kern-/Erweiterungs-Set)
        PaperCard byName = db.getCard(prints.get(0).getName());
        if (byName == null) {
            byName = db.getCardFromEditions(prints.get(0).getName(), CardDb.CardArtPreference.LATEST_ART_ALL_EDITIONS,
                    IPaperCard.DEFAULT_ART_INDEX, null);
        }
        return byName != null ? byName : prints.get(0);
    }

    /** Druck im gewuenschten Set (mit Nummer/Art, wenn vorhanden), sonst null. */
    private static PaperCard pickPrint(List<PaperCard> prints, String set, String num, int art) {
        if (set == null || set.isBlank()) {
            return null;
        }
        List<CardEdition> eds = editionsOf(set);
        if (eds.isEmpty()) {
            return null;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (CardEdition e : eds) {
            codes.add(e.getCode().toUpperCase(Locale.ROOT));
            if (e.getCode2() != null) {
                codes.add(e.getCode2().toUpperCase(Locale.ROOT));
            }
        }
        List<PaperCard> inSet = new ArrayList<>();
        for (PaperCard c : prints) {
            if (codes.contains(c.getEdition().toUpperCase(Locale.ROOT))) {
                inSet.add(c);
            }
        }
        if (inSet.isEmpty()) {
            return null;
        }
        if (num != null && !num.isBlank()) {
            for (String variant : numberVariants(num)) {
                for (PaperCard c : inSet) {
                    if (variant.equalsIgnoreCase(c.getCollectorNumber())) {
                        return c;
                    }
                }
            }
        }
        if (art > 0) {
            for (PaperCard c : inSet) {
                if (c.getArtIndex() == art) {
                    return c;
                }
            }
        }
        // Set bekannt, Nummer nicht (oder keine): erster Druck mit Standard-Art
        for (PaperCard c : inSet) {
            if (c.getArtIndex() == IPaperCard.DEFAULT_ART_INDEX) {
                return c;
            }
        }
        return inSet.get(0);
    }

    /** Editionen zu einem Set-Code: Scryfall-Code, dann Forge-Code/Code2/Alias; leer = unbekannt. */
    static List<CardEdition> editionsOf(String code) {
        List<CardEdition> l = Holder.BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
        return l == null ? List.of() : l;
    }

    /** Rohnummer plus die Schreibweisen, in denen XMage/Moxfield sie anders als Scryfall/Forge fuehren. */
    static List<String> numberVariants(String num) {
        String n = num.trim();
        Set<String> out = new LinkedHashSet<>();
        out.add(n);
        String mapped = n.replace('*', '★').replace('+', '†').replace("Ph", "Φ");
        out.add(mapped);
        String stripped = n.replaceFirst("^0+(?=\\d)", "");
        out.add(stripped);
        out.add(mapped.replaceFirst("^0+(?=\\d)", ""));
        // Scryfall fuehrt manche Nummern mit "s"/"p"-Suffix, Forge ohne und umgekehrt
        if (stripped.matches("\\d+[sp]")) {
            out.add(stripped.substring(0, stripped.length() - 1));
        }
        return new ArrayList<>(out);
    }

    /** Kartennamen-Kandidaten in Suchreihenfolge: voller Name, " // "-Form, Vorderseite, ohne Akzente. */
    static List<String> nameCandidates(String n) {
        Set<String> out = new LinkedHashSet<>();
        out.add(n);
        if (n.contains("/")) {
            String[] parts = SPLIT_SEP.split(n);
            if (parts.length >= 2) {
                out.add(String.join(" // ", parts));
            }
            out.add(frontFace(n));
        }
        // Forge schreibt "Aether Vial", Scryfall/Moxfield "Æther Vial"; Akzente: Forge fuehrt sie, Listen oft nicht
        String plain = StringUtils.stripAccents(n).replace("Æ", "Ae").replace("æ", "ae");
        if (!plain.equals(n)) {
            out.add(plain);
            if (n.contains("/")) {
                out.add(frontFace(plain));
            }
        }
        return new ArrayList<>(out);
    }

    /** Vorderseite ({@code "A // B"} bzw. {@code "A / B"} -&gt; {@code "A"}). */
    static String frontFace(String name) {
        int i = name.indexOf('/');
        return i > 0 ? name.substring(0, i).trim() : name.trim();
    }

    static String normalizeName(String name) {
        return name.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
                .replace('\u00a0', ' ').strip().replaceAll("\\s+", " ");
    }

    // ----------------------------------------------------------------------------------------------- Kennzahlen je Karte

    /** Scryfall-Set-Code in Grossbuchstaben ("" = unbekannt). */
    public static String scryfallSet(PaperCard pc) {
        if (pc == null) {
            return "";
        }
        CardEdition e = StaticData.instance().getEditions().get(pc.getEdition());
        if (e == null) {
            return "";
        }
        return e.getScryfallCode().toUpperCase(Locale.ROOT);
    }

    /** Sammlernummer ("" = keine). */
    public static String number(PaperCard pc) {
        if (pc == null) {
            return "";
        }
        String n = pc.getCollectorNumber();
        return n == null || n.isBlank() || IPaperCard.NO_COLLECTOR_NUMBER.equals(n) ? "" : n;
    }

    /** creature &gt; land &gt; planeswalker &gt; battle &gt; instant &gt; sorcery &gt; artifact &gt; enchantment &gt; other */
    public static String kindOf(PaperCard pc) {
        var t = pc.getRules().getType();
        if (t.isCreature()) return "creature";
        if (t.isLand()) return "land";
        if (t.isPlaneswalker()) return "planeswalker";
        if (t.isBattle()) return "battle";
        if (t.isInstant()) return "instant";
        if (t.isSorcery()) return "sorcery";
        if (t.isArtifact()) return "artifact";
        if (t.isEnchantment()) return "enchantment";
        return "other";
    }

    /** Kann Commander sein (legendaere Kreatur, "can be your commander", Background, ...). */
    public static boolean canBeCommander(PaperCard pc) {
        return pc != null && pc.getRules().canBeCommander();
    }

    /** Farbidentitaet der Commander als "WUBRG"-Teilmenge ("" = farblos oder unbekannt). Namen werden nur nach Name gesucht. */
    public static String colors(List<String> commanderNames) {
        boolean w = false, u = false, b = false, r = false, g = false;
        for (String c : commanderNames) {
            PaperCard pc = resolve(c, null, null);
            if (pc == null) {
                continue;
            }
            var id = pc.getRules().getColorIdentity();
            w |= id.hasWhite();
            u |= id.hasBlue();
            b |= id.hasBlack();
            r |= id.hasRed();
            g |= id.hasGreen();
        }
        return (w ? "W" : "") + (u ? "U" : "") + (b ? "B" : "") + (r ? "R" : "") + (g ? "G" : "");
    }

    /** Regeltext aller Seiten, klein geschrieben, Leerraum zu einem Leerzeichen (Bracket-Analyse). */
    public static String oracle(PaperCard pc) {
        CardRules rules = pc.getRules();
        StringBuilder sb = new StringBuilder();
        for (ICardFace f : rules.getAllFaces()) {
            String t = f.getOracleText();
            if (t != null) {
                sb.append(t).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Alle Kartennamen (Hauptnamen, einmal je Karte) fuer "Meintest du ...?". Einmal gebaut, danach gecacht. */
    public static List<String> allNames() {
        List<String> n = Holder.names;
        if (n == null) {
            Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (PaperCard pc : db().getUniqueCards()) {
                set.add(pc.getName());
            }
            n = List.copyOf(set);
            Holder.names = n;
        }
        return n;
    }

    /** Index vorbauen (Warmup-Thread): Set-Codes und Namensliste. */
    public static void warmup() {
        Holder.BY_CODE.size();
        allNames();
    }
}
