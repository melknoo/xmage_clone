package dev.magelite.game;

/**
 * Bot-Tempo: Denkzeit der Forge-KI und Pausen nach Bot-Aktionen (damit Menschen sehen, was passiert). Live änderbar
 * (volatile Felder).
 */
public final class TempoSettings {

    public enum Preset {
        BLITZ(2, 0, 150),
        NORMAL(4, 350, 500),
        BEDACHT(8, 500, 700),
        MAX(15, 600, 800);

        /** Forge {@code Game.AI_TIMEOUT} (Sekunden je KI-Entscheidung) */
        public final int thinkSecs;
        /** Pause nach einer Bot-Aktion (Zauber, Fähigkeit, Land) */
        public final int actionDelayMs;
        /** Pause nach Angriffs-/Block-Erklärung eines Bots */
        public final int combatDelayMs;

        Preset(int thinkSecs, int actionDelayMs, int combatDelayMs) {
            this.thinkSecs = thinkSecs;
            this.actionDelayMs = actionDelayMs;
            this.combatDelayMs = combatDelayMs;
        }
    }

    private volatile Preset preset;
    private volatile int thinkSecs;
    private volatile int actionDelayMs;
    private volatile int combatDelayMs;

    public TempoSettings(Preset preset) {
        apply(preset);
    }

    public void apply(Preset p) {
        this.preset = p;
        this.thinkSecs = p.thinkSecs;
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
