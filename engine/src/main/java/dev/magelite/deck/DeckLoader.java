package dev.magelite.deck;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.Set;
import java.util.TreeSet;

/**
 * Baut aus Decktext ein spielbares Forge-Commander-Deck ({@link LoadedDeck}) und prueft es mit
 * {@code DeckFormat.Commander.getDeckConformanceProblem} (Groesse, Farbidentitaet, Partner, Bans, Kopienzahl).
 * <p>
 * {@link #fromText} versteht alle Formate des {@link TextDeckParser} (MageLite-Decktext v2, Forge-.dck, altes XMage-.dck,
 * MTGA/Moxfield/Archidekt-Listen). {@link LoadedDeck#text()} ist immer der normalisierte Decktext v2.
 */
public final class DeckLoader {

    private DeckLoader() {
    }

    /**
     * Laedt ein Deck aus Text (gespeicherte eigene Decks, Sample-Decks, Host-Link, Import).
     * Unbekannte Karten werden ausgelassen: {@code valid=false}, Validierungstext "Unbekannte Karten: ...".
     *
     * @param name   Anzeigename; null/leer = im Text hinterlegter Name bzw. Commander
     * @param source Herkunft fuers Log ({@code "user:12"}, {@code "sample:..."}, ...)
     */
    public static LoadedDeck fromText(String text, String name, String source) {
        TextDeckParser.Result r = TextDeckParser.parse(text == null ? "" : text, name, null);
        return fromParsed(r, source);
    }

    /** Alias fuer {@link #fromText} (Aufrufer aus der Zeit des XMage-.dck). */
    public static LoadedDeck fromDckText(String text, String name, String source) {
        return fromText(text, name, source);
    }

    /** Aus einem schon geparsten Deck (Import-Vorschau/Speichern, ohne erneut zu parsen). */
    public static LoadedDeck fromParsed(TextDeckParser.Result r, String source) {
        Deck deck = toForgeDeck(r);
        String problem = null;
        if (r.commanders().isEmpty()) {
            problem = "Kein Commander gewählt";
        } else {
            try {
                String p = DeckFormat.Commander.getDeckConformanceProblem(deck);
                problem = p == null ? null : prettyProblem(p);
            } catch (RuntimeException e) {
                problem = "Prüfung fehlgeschlagen: " + e;
            }
        }
        String banned = bannedProblem(deck);
        String unknownText = r.unknown().isEmpty() ? "" : "Unbekannte Karten: " + String.join(", ", r.unknown());
        StringBuilder validation = new StringBuilder(unknownText);
        for (String p : new String[]{problem, banned}) {
            if (p != null) {
                if (validation.length() > 0) {
                    validation.append('\n');
                }
                validation.append(p);
            }
        }
        boolean valid = problem == null && banned == null && r.unknown().isEmpty();
        int mainCount = deck.getMain().countAll();
        return new LoadedDeck(r.name(), source, deck, LoadedDeck.commanderNames(deck), mainCount, valid,
                valid ? "" : validation.toString(), unknownText, r.toText());
    }

    /** Forge-Deck: Hauptdeck in {@code Main}, Commander in {@code DeckSection.Commander}. */
    public static Deck toForgeDeck(TextDeckParser.Result r) {
        Deck deck = new Deck(r.name());
        CardPool main = deck.getMain();
        for (TextDeckParser.Resolved c : r.main()) {
            PaperCard pc = c.printing();
            if (pc != null) {
                main.add(pc, c.count());
            }
        }
        if (!r.commanders().isEmpty()) {
            CardPool cmd = deck.getOrCreate(DeckSection.Commander);
            for (TextDeckParser.Resolved c : r.commanders()) {
                PaperCard pc = c.printing();
                if (pc != null) {
                    cmd.add(pc, c.count());
                }
            }
        }
        return deck;
    }

    /**
     * Commander-Bannliste: {@code getDeckConformanceProblem} prueft sie nicht (nur Groesse, Farbidentitaet, Partner,
     * Kopien); Forge fragt sie ueber {@code DeckFormat.isLegalCard} ab (Commander-Praedikat aus {@code res/formats}).
     */
    private static String bannedProblem(Deck deck) {
        Set<String> illegal = new TreeSet<>();
        try {
            for (DeckSection section : new DeckSection[]{DeckSection.Commander, DeckSection.Main}) {
                if (!deck.has(section)) {
                    continue;
                }
                for (var e : deck.get(section)) {
                    if (!DeckFormat.Commander.isLegalCard(e.getKey())) {
                        illegal.add(e.getKey().getName());
                    }
                }
            }
        } catch (RuntimeException e) {
            return "Prüfung der Bannliste fehlgeschlagen: " + e;
        }
        return illegal.isEmpty() ? null : "Deck contains cards banned in Commander: " + String.join(", ", illegal);
    }

    /** Forges Meldungen lesen sich als Satzfortsetzung ("should have at least 99 cards"): "Deck " voranstellen. */
    private static String prettyProblem(String p) {
        String s = p.strip();
        if (s.isEmpty()) {
            return "Deck ungültig";
        }
        return Character.isUpperCase(s.charAt(0)) ? s : "Deck " + s;
    }
}
