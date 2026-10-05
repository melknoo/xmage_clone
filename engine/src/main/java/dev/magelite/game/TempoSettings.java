package dev.magelite.game;

import java.io.Serializable;

/**
 * Bot-Tempo. Wird zwischen allen Kopien eines Bots geteilt (AI-Simulationen kopieren Spieler),
 * daher live aenderbar ueber die volatile Felder.
 */
public final class TempoSettings implements Serializable {

    public enum Preset {
        BLITZ(1, 2, true, true, false, 0, 150),
        NORMAL(2, 4, true, true, true, 350, 500),
        BEDACHT(5, 8, false, false, false, 500, 700),
        MAX(7, 15, false, false, false, 600, 800);

        public final int skill;
        public final int thinkSecs;
        public final boolean fastOpponentTurns;
        /** ohne Spielbares sofort passen; bei gleichen Stapelobjekten nur einmal nachdenken (siehe MageLiteBot) */
        public final boolean fastStack;
        /**
         * trotz fastOpponentTurns in fremden Kampfschritten nachdenken, wenn eine Spontanaktion moeglich ist
         * (Kampftricks, Removal auf Angreifer); ohne fastOpponentTurns ohne Wirkung (dann rechnet der Bot ohnehin)
         */
        public final boolean reactInCombat;
        public final int actionDelayMs;
        public final int combatDelayMs;

        Preset(int skill, int thinkSecs, boolean fastOpponentTurns, boolean fastStack, boolean reactInCombat,
               int actionDelayMs, int combatDelayMs) {
            this.skill = skill;
            this.thinkSecs = thinkSecs;
            this.fastOpponentTurns = fastOpponentTurns;
            this.fastStack = fastStack;
            this.reactInCombat = reactInCombat;
            this.actionDelayMs = actionDelayMs;
            this.combatDelayMs = combatDelayMs;
        }
    }

    private volatile Preset preset;
    private volatile int thinkSecs;
    private volatile boolean fastOpponentTurns;
    private volatile boolean fastStack;
    private volatile boolean reactInCombat;
    private volatile int actionDelayMs;
    private volatile int combatDelayMs;

    public TempoSettings(Preset preset) {
        apply(preset);
    }

    public void apply(Preset p) {
        this.preset = p;
        this.thinkSecs = p.thinkSecs;
        this.fastOpponentTurns = p.fastOpponentTurns;
        this.fastStack = p.fastStack;
        this.reactInCombat = p.reactInCombat;
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

    public boolean fastStack() {
        return fastStack;
    }

    public void setFastStack(boolean fastStack) {
        this.fastStack = fastStack;
    }

    public boolean reactInCombat() {
        return reactInCombat;
    }

    public void setReactInCombat(boolean reactInCombat) {
        this.reactInCombat = reactInCombat;
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
