package dev.magelite.stats;

import java.util.List;

/**
 * Level-Kurve, Titel und Deck-Meisterschaft (reine Meta-Progression, kein Gameplay-Einfluss).
 */
public final class Progression {

    private Progression() {
    }

    public record Level(int level, int xpIntoLevel, int xpForNext) {
    }

    public record Title(int level, String title) {
    }

    public static final List<Title> TITLES = List.of(
            new Title(1, "Novize"),
            new Title(3, "Lehrling"),
            new Title(5, "Adept"),
            new Title(8, "Zauberwirker"),
            new Title(12, "Magier"),
            new Title(16, "Kampfmagier"),
            new Title(20, "Erzmagier"),
            new Title(25, "Großmeister"),
            new Title(30, "Planeswalker"),
            new Title(40, "Weltenwandler"),
            new Title(50, "Mythische Legende"),
            new Title(75, "Ältester Drache"),
            new Title(100, "Unsterblich"));

    /** XP von Level L nach L+1. */
    public static int xpForLevel(int level) {
        return (int) Math.round(150 * Math.pow(level, 1.35));
    }

    public static Level levelOf(long xpTotal) {
        int level = 1;
        long rest = xpTotal;
        while (rest >= xpForLevel(level)) {
            rest -= xpForLevel(level);
            level++;
        }
        return new Level(level, (int) rest, xpForLevel(level));
    }

    public static String titleOf(int level) {
        String t = TITLES.get(0).title();
        for (Title x : TITLES) {
            if (level >= x.level()) {
                t = x.title();
            }
        }
        return t;
    }

    public static Title nextTitle(int level) {
        for (Title x : TITLES) {
            if (x.level() > level) {
                return x;
            }
        }
        return null;
    }

    private static final int[] MASTERY = {0, 200, 500, 900, 1500, 2300, 3300, 4600, 6200, 8200};

    public static int masteryLevel(int xp) {
        int l = 1;
        for (int i = 0; i < MASTERY.length; i++) {
            if (xp >= MASTERY[i]) {
                l = i + 1;
            }
        }
        return l;
    }

    /** XP-Schwelle der naechsten Meisterschaftsstufe (oder der letzten). */
    public static int masteryNext(int xp) {
        for (int t : MASTERY) {
            if (t > xp) {
                return t;
            }
        }
        return MASTERY[MASTERY.length - 1];
    }

    public static double tempoFactor(String tempo) {
        return switch (tempo == null ? "" : tempo) {
            case "BLITZ" -> 0.9;
            case "BEDACHT" -> 1.15;
            case "MAX" -> 1.3;
            default -> 1.0;
        };
    }

    public static int placementXp(int place) {
        return switch (place) {
            case 1 -> 150;
            case 2 -> 70;
            case 3 -> 35;
            default -> 0;
        };
    }
}
