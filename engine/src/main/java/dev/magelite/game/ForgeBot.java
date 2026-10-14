package dev.magelite.game;

import dev.magelite.boot.ForgeBoot;
import forge.LobbyPlayer;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.cost.CostPayment;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import org.apache.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Forge-KI eines Bot-Sitzes (Profil {@link ForgeBoot#AI_PROFILE}). Vor jeder Entscheidung ein Safe-Point (inbox,
 * gedrosselter State, "denkt"-Status); nach sichtbaren Aktionen eine Pause (Tempo) vor der naechsten Entscheidung.
 */
public final class ForgeBot extends PlayerControllerAi {

    private static final Logger LOG = Logger.getLogger(ForgeBot.class);

    private final GameHost host;
    /** Karten-ids, deren Wirken in Zug {@link #failedTurn} gescheitert ist (siehe {@link #doomed}). */
    private final Set<Integer> failedCards = new HashSet<>();
    private int failedTurn = -1;

    ForgeBot(Game game, Player player, LobbyPlayer lobby, GameHost host) {
        super(game, player, lobby);
        this.host = host;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        host.safePoint();
        if (getGame().isGameOver()) {
            return null;
        }
        host.botThinking(getPlayer(), true);
        List<SpellAbility> chosen;
        try {
            chosen = super.chooseSpellAbilityToPlay();
            // Zauber, deren Bezahlung sicher scheitert: ueberspringen (Forge filtert isSkip in der naechsten Wahl und
            // gibt die Sperre danach selbst wieder frei) und neu waehlen; bleibt es dabei, lieber passen
            for (int retry = 0; chosen != null; retry++) {
                SpellAbility doomed = doomed(chosen);
                if (doomed == null) {
                    break;
                }
                doomed.setSkip(true);
                chosen = retry < 3 ? super.chooseSpellAbilityToPlay() : null;
            }
        } finally {
            host.botThinking(getPlayer(), false);
        }
        if (chosen != null && chosen.stream().anyMatch(sa -> !sa.isManaAbility())) {
            host.requestPause(host.getTempo().actionDelayMs()); // Aktion erst sichtbar machen, dann weiter
        }
        return chosen;
    }

    /**
     * Ein gewaehlter Zauber, der nicht gewirkt werden kann: Karte ist in diesem Zug schon einmal gescheitert
     * ({@link #playChosenSpellAbility}), oder Zauber aus der Kommandozone, den die KI mit der Steuer nicht bezahlen kann.
     * Forges KI prueft die Kosten alternativer Seiten (Abenteuer, MDFC-Rueckseite) an einer LKI-Kopie ohne Zone und
     * vergisst dabei die Commander-Steuer (Kellan, the Fae-Blooded: Birthright Boon {1}{W} statt {3}{W}).
     */
    private SpellAbility doomed(List<SpellAbility> chosen) {
        int turn = getGame().getPhaseHandler().getTurn();
        if (turn != failedTurn) {
            failedTurn = turn;
            failedCards.clear();
        }
        for (SpellAbility sa : chosen) {
            Card card = sa.getHostCard();
            if (!sa.isSpell() || card == null || card.isCopiedSpell()) {
                continue;
            }
            if (failedCards.contains(card.getId())
                    || (card.isInZone(ZoneType.Command) && !ComputerUtilCost.canPayCost(sa, getPlayer(), false))) {
                return sa;
            }
        }
        return null;
    }

    /**
     * Forge-Fehler im KI-Pfad ({@code ComputerUtil.handlePlayingSpellAbility}): Scheitert die Bezahlung (oder fehlt ein
     * Modus/Ziel), bleibt die Karte ohne Stapelobjekt in der Stapel-Zone ("Wird gewirkt", Commander verloren) und der
     * Stapel eingefroren - Forges Rueckroll-Bedingung prueft die falsche Zone. Dann wie beim Abbruch eines Menschen
     * ({@code PlaySpellAbility.playAbility}) zurueckrollen, den Stapel freigeben und die Karte fuer diesen Zug sperren
     * (sonst versucht die KI es bei jeder Prioritaet erneut).
     */
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        Card card = sa.getHostCard();
        if (!sa.isSpell() || card == null || card.isCopiedSpell()) {
            return super.playChosenSpellAbility(sa);
        }
        Game game = getGame();
        Zone from = game.getZoneOf(card);
        int pos = from == null ? 0 : from.getCards().indexOf(card);
        boolean refreeze = game.getStack().isFrozen();
        boolean played = super.playChosenSpellAbility(sa);
        if (from == null || from.is(ZoneType.Stack)) {
            return played;
        }
        Card now = game.getCardState(card);
        if (now == null || !now.isInZone(ZoneType.Stack)) {
            return played;
        }
        for (SpellAbilityStackInstance si : game.getStack()) {
            if (si.isSpell() && si.getSourceCard() != null && si.getSourceCard().getId() == now.getId()) {
                return played;
            }
        }
        String what = getPlayer() + ": " + now.getName() + " (" + sa + ") aus " + from.getZoneType() + ", Kosten "
                + (sa.getPayCosts() == null ? "?" : sa.getPayCosts().getTotalMana()) + " ohne Steuer, Pool "
                + getPlayer().getManaPool().totalMana() + ", Mana geschaetzt " + ComputerUtilMana.getAvailableManaEstimate(getPlayer());
        if (refreeze) {
            // Stapel war schon eingefroren: der Zauber kann regulaer auf den eingefrorenen Stapel gewartet haben
            LOG.warn("KI-Zauber ohne Stapelobjekt, Stapel war eingefroren (nicht zurueckgerollt): " + what);
            return played;
        }
        LOG.warn("KI konnte Zauber nicht wirken, zurueckgerollt: " + what);
        GameActionUtil.rollbackAbility(sa, from, pos, new CostPayment(sa.getPayCosts(), sa), card);
        game.getStack().unfreezeStack();
        sa.setSkip(true);
        failedCards.add(card.getId());
        return played;
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String message, boolean addSuffix) {
        host.noteReveal(getPlayer(), message, cards, owner);
        super.reveal(cards, zone, owner, message, addSuffix);
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        host.safePoint();
        super.declareAttackers(attacker, combat);
        if (!combat.getAttackers().isEmpty()) {
            host.requestPause(host.getTempo().combatDelayMs());
        }
        host.onUpdate();
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        host.safePoint();
        super.declareBlockers(defender, combat);
        if (!combat.getAllBlockers().isEmpty()) {
            host.requestPause(host.getTempo().combatDelayMs());
        }
        host.onUpdate();
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        ScenarioHooks sc = host.scenario();
        Player p = sc == null ? null : sc.startingPlayer(host);
        return p != null ? p : getPlayer();
    }

    /** Lobby-Spieler, der fuer einen Bot-Sitz den {@link ForgeBot} erzeugt (ohne Simulation). */
    static final class Lobby extends LobbyPlayerAi {
        private final GameHost host;

        Lobby(String name, GameHost host, String profile) {
            super(name, null);
            this.host = host;
            setAiProfile(profile == null ? ForgeBoot.AI_PROFILE : profile);
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player ai = new Player(getName(), game, id);
            ForgeBot bot = new ForgeBot(game, ai, this, host);
            bot.getAi().setUseSimulation(null);
            ai.setFirstController(bot);
            return ai;
        }
    }
}
