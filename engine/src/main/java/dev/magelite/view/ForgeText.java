package dev.magelite.view;

import java.util.regex.Pattern;

/**
 * Forge-Texte (Spielverlauf, Stapel) fuer die Anzeige glaetten: Forge haengt an Kartennamen die interne Objekt-Nummer
 * ("Mountain (341)") und an Ausloeser den Kontext ("... [Attacker: Jeleva (100)]"). XMage hatte beides nicht.
 */
public final class ForgeText {

    /** " (341)" direkt hinter einem Namen; "Creature 4 / 7" oder "(Whenever ...)" bleiben */
    private static final Pattern OBJ_ID = Pattern.compile("(?<=[\\p{L}\\p{N}'’)])\\s\\(\\d{1,7}\\)(?=$|[\\s.,;:!?\\])])");
    /** Kontext-Klammern am Ende: " [Zone Changer: X]", " [Attacker: Y]" (auch mehrere) */
    private static final Pattern CONTEXT = Pattern.compile("(\\s*\\[[A-Z][A-Za-z ]{1,30}:[^\\[\\]]*\\])+\\s*$");

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
}
