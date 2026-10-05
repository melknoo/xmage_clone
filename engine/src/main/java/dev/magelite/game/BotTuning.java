package dev.magelite.game;

import mage.player.ai.score.GameStateEvaluator2;
import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KI-Verbesserungen fuer Commander FFA, pro Spieler abschaltbar (fuer A/B-Vergleiche in der Bot-Arena).
 * <p>
 * Standard: alles an. Schluessel ist die Spieler-ID - KI-Simulationen kopieren die Spieler mit derselben ID, daher
 * gilt die Einstellung auch in der Suche. Statischer Zustand statt Feld am Bot, weil die Bewertung
 * ({@code GameStateEvaluator2.evaluate}) statisch aufgerufen wird und nur die ID kennt.
 */
public final class BotTuning {

    private static final Logger LOG = Logger.getLogger(BotTuning.class);

    public enum Lever {
        /** Bewertung gegen alle Gegner statt nur den ersten ({@code mage.player.ai.score.GameStateEvaluator2}) */
        FFA_EVAL,
        /** Angriffsziel unter allen Gegnern waehlen ({@link FfaAttack}) */
        FFA_ATTACK,
        /** in fremden Kampfschritten mit Spontanaktion nachdenken ({@link TempoSettings.Preset#reactInCombat}) */
        REACT_IN_COMBAT
    }

    private static final Map<UUID, Set<Lever>> DISABLED = new ConcurrentHashMap<>();

    /** Gewicht des staerksten Gegners in der FFA-Bewertung; Rest = Durchschnitt ueber alle Gegner */
    public static volatile double ffaMaxWeight = 0.5;
    /** Bonus pro ausgeschiedenem Gegner (zum Vergleich: 20 Leben = 10000, ein Permanent ca. 300-1000) */
    public static volatile int ffaEliminationBonus = 5000;

    private BotTuning() {
    }

    public static boolean enabled(UUID playerId, Lever lever) {
        if (playerId == null) {
            return true;
        }
        Set<Lever> off = DISABLED.get(playerId);
        return off == null || !off.contains(lever);
    }

    public static void disable(UUID playerId, Set<Lever> levers) {
        if (!levers.isEmpty()) {
            DISABLED.put(playerId, EnumSet.copyOf(levers));
        }
    }

    public static void clear(UUID playerId) {
        DISABLED.remove(playerId);
    }

    /**
     * Prueft beim Start, ob unsere {@code GameStateEvaluator2} geladen wurde und nicht die aus dem XMage-Jar
     * (Classpath-Reihenfolge: Engine-Jar muss vor den XMage-Jars stehen).
     */
    public static boolean checkFfaEvaluator() {
        String where = String.valueOf(GameStateEvaluator2.class.getProtectionDomain().getCodeSource().getLocation());
        try {
            Field f = GameStateEvaluator2.class.getField("MAGELITE_FFA");
            if (f.getBoolean(null)) {
                LOG.info("KI-Bewertung: MageLite-FFA aktiv (" + where + ")");
                return true;
            }
        } catch (ReflectiveOperationException ignored) {
            // Original-Klasse ohne Marker
        }
        LOG.warn("KI-Bewertung: Original-XMage-Bewertung aktiv (nur 1 Gegner) - Classpath-Reihenfolge pruefen, geladen aus " + where);
        return false;
    }
}
