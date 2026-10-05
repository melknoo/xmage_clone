package dev.magelite.game;

import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.keyword.DoubleStrikeAbility;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.abilities.keyword.IndestructibleAbility;
import mage.abilities.keyword.VigilanceAbility;
import mage.counters.CounterType;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.player.ai.CombatEvaluator;
import mage.player.ai.util.CombatUtil;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Angriffe fuer Commander FFA. Ersetzt {@code ComputerPlayer6.declareAttackers}: das schickt alle "sicheren"
 * Angreifer an den ersten Gegner der Sitzliste und prueft sie nur gegen dessen Blocker.
 * <ol>
 *   <li>Lethal gegen irgendeinen Gegner (wenigstes Leben zuerst) - alles rein, wie im Original.</li>
 *   <li>Pro Gegner: welche Angreifer sind gegen SEINE Blocker sicher (kein schlechter Tausch)?</li>
 *   <li>Ziel: groesster Anteil an seinem Leben, gewichtet mit seiner Bedrohung (Board-Wert).</li>
 *   <li>Gegenschlag: so viele Blocker zurueckhalten, dass der staerkste Gegner uns naechsten Zug nicht toetet.</li>
 * </ol>
 * Laeuft nur ausserhalb von Simulationen auf dem Spiel-Thread.
 */
final class FfaAttack {

    private static final Logger LOG = Logger.getLogger(FfaAttack.class);

    private FfaAttack() {
    }

    static void declareAttackers(Player me, Game game) {
        UUID myId = me.getId();
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, myId));
        if (game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_ATTACKERS, myId, myId))) {
            return;
        }

        List<Player> defenders = new ArrayList<>();
        for (UUID id : game.getOpponents(myId, true)) {
            Player p = game.getPlayer(id);
            if (p != null && p.isInGame()) {
                defenders.add(p);
            }
        }
        if (defenders.isEmpty()) {
            return;
        }

        // 1. Lethal (wie Original: alles auf den Gegner, der faellt)
        defenders.sort(Comparator.comparingInt(Player::getLife));
        for (Player def : defenders) {
            List<Permanent> attackers = me.getAvailableAttackers(def.getId(), game);
            if (attackers.isEmpty()) {
                continue;
            }
            List<Permanent> killers = CombatUtil.canKillOpponent(game, attackers, def.getAvailableBlockers(game), def);
            if (!killers.isEmpty()) {
                for (Permanent attacker : killers) {
                    me.declareAttacker(attacker.getId(), def.getId(), game, false);
                }
                log(me, "Lethal auf " + def.getName() + " mit " + killers.size());
                return;
            }
        }

        // 2. sichere Angreifer pro Gegner
        CombatEvaluator eval = new CombatEvaluator();
        Map<Player, List<Permanent>> safe = new LinkedHashMap<>();
        Set<UUID> candidates = new HashSet<>();
        for (Player def : defenders) {
            List<Permanent> blockers = def.getAvailableBlockers(game);
            List<Permanent> ok = new ArrayList<>();
            for (Permanent attacker : me.getAvailableAttackers(def.getId(), game)) {
                if (isSafe(attacker, blockers, game, eval)) {
                    ok.add(attacker);
                }
            }
            safe.put(def, ok);
            ok.forEach(a -> candidates.add(a.getId()));
        }
        if (candidates.isEmpty()) {
            return;
        }

        // 4. Gegenschlag: Blocker zurueckhalten (vor der Zielwahl, damit die Reserve nirgends angreift)
        Set<UUID> reserve = reserveBlockers(me, defenders, candidates, game);

        // 3. Ziele nach Wert; jeder Angreifer geht an den besten Gegner, gegen den er sicher ist
        Map<Player, Double> threat = new LinkedHashMap<>();
        double totalThreat = 0;
        for (Player def : defenders) {
            double t = Math.max(0, boardScore(def.getId(), game));
            threat.put(def, t);
            totalThreat += t;
        }
        Map<Player, Double> value = new LinkedHashMap<>();
        for (Player def : defenders) {
            int power = 0;
            for (Permanent a : safe.get(def)) {
                if (!reserve.contains(a.getId())) {
                    power += Math.max(0, a.getPower().getValue());
                }
            }
            double share = totalThreat > 0 ? threat.get(def) / totalThreat : 1.0 / defenders.size();
            value.put(def, power / (double) Math.max(1, def.getLife()) * (0.5 + share));
        }
        List<Player> order = new ArrayList<>(defenders);
        order.sort(Comparator.comparingDouble((Player p) -> value.get(p)).reversed());

        int declared = 0;
        StringBuilder info = new StringBuilder();
        for (Player def : order) {
            List<Permanent> mine = new ArrayList<>();
            for (Permanent a : safe.get(def)) {
                if (!reserve.contains(a.getId()) && !a.isAttacking()) {
                    mine.add(a);
                }
            }
            if (mine.isEmpty()) {
                continue;
            }
            int n = attackWith(me, def, mine, game);
            declared += n;
            info.append(' ').append(def.getName()).append('=').append(n);
        }
        if (declared > 0 || !reserve.isEmpty()) {
            log(me, "greift an:" + info + (reserve.isEmpty() ? "" : " | Reserve " + reserve.size()));
        }
    }

    /** Planeswalker/Battles des Gegners zuerst (wie Original), Rest auf den Spieler. */
    private static int attackWith(Player me, Player def, List<Permanent> attackers, Game game) {
        int count = 0;
        List<Permanent> permanentDefenders = new ArrayList<>();
        game.getBattlefield().getActivePermanents(StaticFilters.FILTER_PERMANENT_PLANESWALKER, me.getId(), game).stream()
                .filter(p -> p.canBeAttacked(null, def.getId(), game))
                .forEach(permanentDefenders::add);
        game.getBattlefield().getActivePermanents(StaticFilters.FILTER_PERMANENT_BATTLE, me.getId(), game).stream()
                .filter(p -> p.canBeAttacked(null, def.getId(), game))
                .forEach(permanentDefenders::add);
        CombatUtil.sortByPower(attackers, false);
        for (Permanent target : permanentDefenders) {
            int counters = target.isPlaneswalker(game)
                    ? target.getCounters(game).getCount(CounterType.LOYALTY)
                    : target.getCounters(game).getCount(CounterType.DEFENSE);
            for (Permanent a : attackers) {
                if (counters <= 0) {
                    break;
                }
                if (a.isAttacking()) {
                    continue;
                }
                me.declareAttacker(a.getId(), target.getId(), game, false);
                if (a.isAttacking()) {
                    counters -= a.getPower().getValue();
                    count++;
                }
            }
        }
        for (Permanent a : attackers) {
            if (!a.isAttacking()) {
                me.declareAttacker(a.getId(), def.getId(), game, false);
                if (a.isAttacking()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Sicher = kein moeglicher Blocker toetet den Angreifer, ohne selbst mindestens gleich viel wert zu sterben.
     * Ausweichfaehigkeiten (Flug, Bedrohung ...) prueft {@code canBlock}.
     */
    static boolean isSafe(Permanent attacker, List<Permanent> blockers, Game game, CombatEvaluator eval) {
        int aPow = attacker.getPower().getValue();
        int aTou = attacker.getToughness().getValue();
        if (aPow <= 0) {
            return false;
        }
        boolean aFirst = has(attacker, FirstStrikeAbility.getInstance(), game) || has(attacker, DoubleStrikeAbility.getInstance(), game);
        boolean aDeath = has(attacker, DeathtouchAbility.getInstance(), game);
        boolean aIndestructible = has(attacker, IndestructibleAbility.getInstance(), game);
        int aValue = -1;
        for (Permanent blocker : blockers) {
            if (!blocker.canBlock(attacker.getId(), game)) {
                continue;
            }
            int bPow = blocker.getPower().getValue();
            int bTou = blocker.getToughness().getValue();
            boolean bFirst = has(blocker, FirstStrikeAbility.getInstance(), game) || has(blocker, DoubleStrikeAbility.getInstance(), game);
            boolean bDeath = has(blocker, DeathtouchAbility.getInstance(), game);
            boolean bIndestructible = has(blocker, IndestructibleAbility.getInstance(), game);

            boolean attackerKills = !bIndestructible && (aPow >= bTou || (aDeath && aPow > 0));
            boolean blockerKills = !aIndestructible && bPow > 0 && (bPow >= aTou || bDeath);
            if (aFirst && !bFirst && attackerKills) {
                blockerKills = false; // Blocker stirbt vor seinem Schaden
            }
            if (bFirst && !aFirst && blockerKills) {
                attackerKills = false;
            }
            if (!blockerKills) {
                continue;
            }
            if (!attackerKills) {
                return false; // wir verlieren den Angreifer fuer nichts
            }
            if (aValue < 0) {
                aValue = eval.evaluate(attacker, game);
            }
            if (eval.evaluate(blocker, game) < aValue) {
                return false; // Tausch zu unseren Ungunsten
            }
        }
        return true;
    }

    /**
     * Haelt so viele Angreifer (hoechste Widerstandskraft zuerst, ohne Wachsamkeit) zurueck, dass kein einzelner
     * Gegner uns mit seinen Kreaturen im naechsten Zug toeten kann (jeder unserer Blocker haelt seinen staerksten
     * Angreifer auf).
     */
    private static Set<UUID> reserveBlockers(Player me, List<Player> defenders, Set<UUID> candidates, Game game) {
        Set<UUID> reserve = new HashSet<>();
        int life = me.getLife();
        List<Permanent> myCreatures = game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, me.getId(), game);
        // Blocker naechste Runde: ungetappt und greift nicht an, oder greift mit Wachsamkeit an
        int fixedBlockers = 0;
        List<Permanent> tapping = new ArrayList<>();
        for (Permanent c : myCreatures) {
            if (c.isTapped()) {
                continue;
            }
            if (!candidates.contains(c.getId()) || has(c, VigilanceAbility.getInstance(), game)) {
                fixedBlockers++;
            } else {
                tapping.add(c);
            }
        }
        tapping.sort(Comparator.comparingInt((Permanent p) -> p.getToughness().getValue()).reversed());

        int worst = 0;
        List<Integer> worstPowers = List.of();
        for (Player def : defenders) {
            List<Integer> powers = new ArrayList<>();
            for (Permanent c : game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, def.getId(), game)) {
                int p = c.getPower().getValue();
                if (p > 0) {
                    powers.add(p);
                }
            }
            powers.sort(Comparator.reverseOrder());
            int total = powers.stream().mapToInt(Integer::intValue).sum();
            if (total > worst) {
                worst = total;
                worstPowers = powers;
            }
        }
        int blockers = fixedBlockers;
        while (incoming(worstPowers, blockers) >= life && reserve.size() < tapping.size()) {
            reserve.add(tapping.get(reserve.size()).getId());
            blockers++;
        }
        return reserve;
    }

    private static int incoming(List<Integer> powersDesc, int blockers) {
        int sum = 0;
        for (int i = blockers; i < powersDesc.size(); i++) {
            sum += powersDesc.get(i);
        }
        return sum;
    }

    private static double boardScore(UUID playerId, Game game) {
        double score = 0;
        for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
            try {
                score += mage.player.ai.score.GameStateEvaluator2.evaluatePermanent(p, game, false);
            } catch (RuntimeException ignored) {
                // einzelne Karte nicht bewertbar - ignorieren
            }
        }
        return score;
    }

    private static boolean has(Permanent p, mage.abilities.Ability ability, Game game) {
        return p.hasAbility(ability, game);
    }

    private static void log(Player me, String msg) {
        if (LOG.isInfoEnabled()) {
            LOG.info(me.getName() + ": " + msg);
        }
    }
}
