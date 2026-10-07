package dev.magelite.game;

import mage.game.Game;
import mage.game.mulligan.LondonMulligan;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * London-Mulligan, der Mulligans pro Spieler zaehlt (fuer Statistiken).
 * Der Zaehler wird zwischen Kopien geteilt; Simulationen zaehlen nicht.
 */
public class TrackingLondonMulligan extends LondonMulligan {

    private final Map<UUID, Integer> mulliganCounts;

    public TrackingLondonMulligan(int freeMulligans) {
        this(freeMulligans, new ConcurrentHashMap<>());
    }

    private TrackingLondonMulligan(int freeMulligans, Map<UUID, Integer> counts) {
        super(freeMulligans);
        this.mulliganCounts = counts;
    }

    @Override
    public void mulligan(Game game, UUID playerId) {
        if (!game.isSimulation()) {
            mulliganCounts.merge(playerId, 1, Integer::sum);
        }
        super.mulligan(game, playerId);
    }

    public int getMulliganCount(UUID playerId) {
        return mulliganCounts.getOrDefault(playerId, 0);
    }

    /** Waere der naechste Mulligan dieses Spielers gratis (erster Mulligan im Commander-Mehrspieler)? */
    public boolean nextMulliganFree(UUID playerId) {
        return freeMulligans > 0 && usedFreeMulligans.getOrDefault(playerId, 0) < freeMulligans;
    }

    @Override
    public TrackingLondonMulligan copy() {
        TrackingLondonMulligan copy = new TrackingLondonMulligan(freeMulligans, mulliganCounts);
        copy.usedFreeMulligans.putAll(usedFreeMulligans);
        // Copy-Konstruktor von LondonMulligan ist package-private -> private Maps per Reflection uebernehmen
        copyPrivateMap(copy, "startingHandSizes");
        copyPrivateMap(copy, "openingHandSizes");
        return copy;
    }

    @SuppressWarnings("unchecked")
    private void copyPrivateMap(TrackingLondonMulligan target, String fieldName) {
        try {
            Field f = LondonMulligan.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            ((Map<UUID, Integer>) f.get(target)).putAll((Map<UUID, Integer>) f.get(this));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("LondonMulligan." + fieldName + " nicht kopierbar", e);
        }
    }
}
