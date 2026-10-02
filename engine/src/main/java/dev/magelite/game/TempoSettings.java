package dev.magelite.game;

import java.io.Serializable;

/**
 * Bot-Tempo. Wird zwischen allen Kopien eines Bots geteilt (AI-Simulationen kopieren Spieler),
 * daher live aenderbar ueber die volatile Felder.
 */
public final class TempoSettings implements Serializable {

    public enum Preset {
        BLITZ(1, 2, true, 0, 150),
        NORMAL(2, 4, true, 350, 500),
        BEDACHT(5, 8, false, 500, 700),
        MAX(7, 15, false, 600, 800);

        public final int skill;
        public final int thinkSecs;
        public final boolean fastOpponentTurns;
        public final int actionDelayMs;
        public final int combatDelayMs;

        Preset(int skill, int thinkSecs, boolean fastOpponentTurns, int actionDelayMs, int combatDelayMs) {
            this.skill = skill;
            this.thinkSecs = thinkSecs;
            this.fastOpponentTurns = fastOpponentTurns;
            this.actionDelayMs = actionDelayMs;
            this.combatDelayMs = combatDelayMs;
        }
    }

    private volatile Preset preset;
    private volatile int thinkSecs;
    private volatile boolean fastOpponentTurns;
    private volatile int actionDelayMs;
    private volatile int combatDelayMs;

    public TempoSettings(Preset preset) {
        apply(preset);
    }

    public void apply(Preset p) {
        this.preset = p;
        this.thinkSecs = p.thinkSecs;
        this.fastOpponentTurns = p.fastOpponentTurns;
        this.actionDelayMs = p.actionDelayMs;
        this.combatDelayMs = p.combatDelayMs;
    }

    public Preset preset() {
        return preset;
    }

    public int thinkSecs() {
        return thinkSecs;
    }

    public void setThinkSecs(int thinkSecs) {
        this.thinkSecs = thinkSecs;
    }

    public boolean fastOpponentTurns() {
        return fastOpponentTurns;
    }

    public void setFastOpponentTurns(boolean fastOpponentTurns) {
        this.fastOpponentTurns = fastOpponentTurns;
    }

    public int actionDelayMs() {
        return actionDelayMs;
    }

    public void setActionDelayMs(int actionDelayMs) {
        this.actionDelayMs = actionDelayMs;
    }

    public int combatDelayMs() {
        return combatDelayMs;
    }

    public void setCombatDelayMs(int combatDelayMs) {
        this.combatDelayMs = combatDelayMs;
    }
}
