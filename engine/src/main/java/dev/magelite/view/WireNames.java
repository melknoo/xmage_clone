package dev.magelite.view;

import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Forge-Werte → Wire-Namen der XMage-Zeit (UI, Test-Werkzeuge und Spikes erwarten sie unveraendert).
 */
public final class WireNames {

    private WireNames() {
    }

    /** {@code state.step} (XMage {@code PhaseStep}); null vor Zug 1. */
    public static String step(PhaseType p) {
        if (p == null) {
            return null;
        }
        return switch (p) {
            case UNTAP -> "UNTAP";
            case UPKEEP -> "UPKEEP";
            case DRAW -> "DRAW";
            case MAIN1 -> "PRECOMBAT_MAIN";
            case COMBAT_BEGIN -> "BEGIN_COMBAT";
            case COMBAT_DECLARE_ATTACKERS -> "DECLARE_ATTACKERS";
            case COMBAT_DECLARE_BLOCKERS -> "DECLARE_BLOCKERS";
            case COMBAT_FIRST_STRIKE_DAMAGE -> "FIRST_COMBAT_DAMAGE";
            case COMBAT_DAMAGE -> "COMBAT_DAMAGE";
            case COMBAT_END -> "END_COMBAT";
            case MAIN2 -> "POSTCOMBAT_MAIN";
            case END_OF_TURN -> "END_TURN";
            case CLEANUP -> "CLEANUP";
        };
    }

    /** {@code state.phase} (XMage {@code TurnPhase}). */
    public static String phase(PhaseType p) {
        if (p == null) {
            return null;
        }
        return switch (p) {
            case UNTAP, UPKEEP, DRAW -> "BEGINNING";
            case MAIN1 -> "PRECOMBAT_MAIN";
            case COMBAT_BEGIN, COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS, COMBAT_FIRST_STRIKE_DAMAGE,
                 COMBAT_DAMAGE, COMBAT_END -> "COMBAT";
            case MAIN2 -> "POSTCOMBAT_MAIN";
            case END_OF_TURN, CLEANUP -> "END";
        };
    }

    /** Zonen-Name (XMage {@code Zone}). */
    public static String zone(ZoneType z) {
        if (z == null) {
            return null;
        }
        return switch (z) {
            case Library -> "LIBRARY";
            case Hand -> "HAND";
            case Graveyard -> "GRAVEYARD";
            case Exile -> "EXILED";
            case Command -> "COMMAND";
            case Stack -> "STACK";
            case Battlefield -> "BATTLEFIELD";
            default -> "OUTSIDE";
        };
    }

    /** Kartentypen wie XMage {@code CardType} (CREATURE, LAND, ...); null wenn keine. */
    public static List<String> types(CardTypeView t) {
        if (t == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (CardType.CoreType c : t.getCoreTypes()) {
            out.add(c.name().toUpperCase(Locale.ROOT));
        }
        return out.isEmpty() ? null : out;
    }

    /** "WUBRG"-Teilmenge; null fuer farblos. */
    public static String colors(ColorSet c) {
        if (c == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (c.hasWhite()) sb.append('W');
        if (c.hasBlue()) sb.append('U');
        if (c.hasBlack()) sb.append('B');
        if (c.hasRed()) sb.append('R');
        if (c.hasGreen()) sb.append('G');
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Zaehlername wie bei XMage: "+1/+1", "-1/-1", "loyalty", sonst klein geschrieben. */
    public static String counter(CounterType t) {
        String n = t.getName();
        if (n == null) {
            return "?";
        }
        return switch (n.toUpperCase(Locale.ROOT)) {
            case "P1P1" -> "+1/+1";
            case "M1M1" -> "-1/-1";
            default -> n.toLowerCase(Locale.ROOT);
        };
    }

    /** Einzelfarbe (MagicColor-Bit) als Wire-Name WHITE ... GREEN. */
    public static String colorName(byte color) {
        return switch (color) {
            case MagicColor.WHITE -> "WHITE";
            case MagicColor.BLUE -> "BLUE";
            case MagicColor.BLACK -> "BLACK";
            case MagicColor.RED -> "RED";
            case MagicColor.GREEN -> "GREEN";
            default -> "COLORLESS";
        };
    }
}
