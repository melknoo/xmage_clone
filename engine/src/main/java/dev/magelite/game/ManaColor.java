package dev.magelite.game;

import forge.card.mana.ManaAtom;

/**
 * Manafarbe einer Client-Antwort ({@code mana:{type}}) bzw. im Manapool. Wire-Namen wie bisher (XMage {@code ManaType}),
 * Forge-Wert = {@link ManaAtom}-Bit (das erwartet {@code InputPayMana.useManaFromPool}).
 */
public enum ManaColor {
    WHITE(ManaAtom.WHITE, "W"),
    BLUE(ManaAtom.BLUE, "U"),
    BLACK(ManaAtom.BLACK, "B"),
    RED(ManaAtom.RED, "R"),
    GREEN(ManaAtom.GREEN, "G"),
    COLORLESS(ManaAtom.COLORLESS, "C");

    /** {@link ManaAtom}-Bit */
    public final byte atom;
    /** Kurzzeichen im State ({@code PlayerDto.mana}) */
    public final String symbol;

    ManaColor(int atom, String symbol) {
        this.atom = (byte) atom;
        this.symbol = symbol;
    }

    /** Wire-Name (z.B. {@code "GREEN"}) oder null. */
    public static ManaColor parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
