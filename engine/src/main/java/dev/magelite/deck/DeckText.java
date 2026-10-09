package dev.magelite.deck;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rein textuelle Umformungen gespeicherter Decktexte (ohne Forge): Bearbeitungsansicht fuer {@code GET /api/decks/{id}/text}.
 */
public final class DeckText {

    /** XMage .dck: {@code 1 [SET:123] Name} */
    private static final Pattern LEGACY = Pattern.compile("^(\\d+)\\s*\\[([^]:]+):([^]]+)]\\s*(.+)$");

    private DeckText() {
    }

    /**
     * Gespeicherter Text -&gt; lesbarer Text zum Bearbeiten ({@code Commander}/{@code Deck}-Abschnitte, ohne {@code NAME:}).
     * Decktext v2 bleibt (bis auf {@code NAME:}/{@code LAYOUT}-Zeilen) unveraendert; Altbestand im XMage-.dck
     * (Format 1, nur wenn die Umstellung fehlschlug) wird nach v2-Schreibweise {@code 1 Name (SET) NUM} gebracht.
     */
    public static String toEditable(String stored) {
        if (stored == null) {
            return "Commander\n\nDeck\n";
        }
        if (isLegacy(stored)) {
            return fromLegacy(stored);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : stored.split("\\r?\\n")) {
            String l = line.stripTrailing();
            if (l.startsWith("NAME:") || l.startsWith("LAYOUT")) {
                continue;
            }
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** Enthaelt der Text XMage-.dck-Zeilen ({@code N [SET:num] Name} oder {@code SB:})? */
    static boolean isLegacy(String text) {
        for (String line : text.split("\\r?\\n")) {
            String l = line.strip();
            if (l.startsWith("SB:") || LEGACY.matcher(l).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String fromLegacy(String dck) {
        StringBuilder cmd = new StringBuilder();
        StringBuilder deck = new StringBuilder();
        for (String line : dck.split("\\r?\\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("NAME:") || l.startsWith("LAYOUT")) {
                continue;
            }
            boolean sb = l.startsWith("SB:");
            if (sb) {
                l = l.substring(3).strip();
            }
            Matcher m = LEGACY.matcher(l);
            if (m.matches()) {
                (sb ? cmd : deck).append(m.group(1)).append(' ').append(m.group(4).strip()).append(" (").append(m.group(2)).append(") ")
                        .append(m.group(3)).append('\n');
            }
        }
        return "Commander\n" + cmd + "\nDeck\n" + deck;
    }
}
