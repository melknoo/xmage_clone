package dev.magelite.game;

import java.util.Deque;

/** Gemeinsame Regeln fuer Chat-Texte (Spiel und Tisch): Saeuberung, Laenge, Rate-Limit. */
public final class ChatText {

    public static final int MAX_LEN = 300;
    public static final int RATE_N = 5;
    public static final long RATE_MS = 5000;

    private ChatText() {
    }

    /** Steuerzeichen raus, trimmen, kuerzen; null, wenn nichts uebrig bleibt. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("\\p{Cntrl}", " ").strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > MAX_LEN ? t.substring(0, MAX_LEN) : t;
    }

    /**
     * Rate-Limit pro Absender: hoechstens {@link #RATE_N} Nachrichten je {@link #RATE_MS}.
     * {@code times} enthaelt die Sendezeitpunkte; der Aufrufer synchronisiert.
     */
    public static boolean allow(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() > RATE_MS) {
            times.pollFirst();
        }
        if (times.size() >= RATE_N) {
            return false;
        }
        times.addLast(now);
        return true;
    }
}
