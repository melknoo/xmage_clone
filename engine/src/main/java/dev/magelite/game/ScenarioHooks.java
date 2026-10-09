package dev.magelite.game;

import forge.game.player.Player;

/**
 * Eingriffe eines Test-Szenarios in den Spielstart (Dev-Modus, Spikes). Alle Methoden laufen auf dem Spiel-Thread.
 */
public interface ScenarioHooks {

    /** Wer beginnt; null = Forge entscheidet. */
    default Player startingPlayer(GameHost host) {
        return null;
    }

    /** Vor der ersten Mulligan-Frage eines Menschen (Hand ist gezogen), z.B. eine Karte in die Starthand tauschen. */
    default void beforeMulligan(GameHost host, Player human) {
    }

    /** Beginn des ersten Zugs (nach Mulligans und Starthand-Aktionen): Karten platzieren. */
    default void startGame(GameHost host) {
    }
}
