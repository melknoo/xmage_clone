package dev.magelite.game;

import dev.magelite.boot.ForgeBoot;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Forge-KI eines Bot-Sitzes (Profil {@link ForgeBoot#AI_PROFILE}). Vor jeder Entscheidung ein Safe-Point (inbox,
 * gedrosselter State, "denkt"-Status); nach Kampf-Erklaerungen der State fuer die Menschen. Pausen (Tempo) folgen in
 * Phase 1.
 */
public final class ForgeBot extends PlayerControllerAi {

    private final GameHost host;

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
        try {
            return super.chooseSpellAbilityToPlay();
        } finally {
            host.botThinking(getPlayer(), false);
        }
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        host.safePoint();
        super.declareAttackers(attacker, combat);
        host.onUpdate();
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        host.safePoint();
        super.declareBlockers(defender, combat);
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
