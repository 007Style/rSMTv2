package com.rsmt.core;

/**
 * Static {@link SimulationControl} for CLI and headless test use.
 * Always: not paused, rSMT enabled, 0 ms tick delay (ludicrous speed).
 */
public final class StaticSimulationControl implements SimulationControl {

    /** Singleton — no state, safe to share. */
    public static final StaticSimulationControl INSTANCE = new StaticSimulationControl();

    private StaticSimulationControl() {}

    @Override
    public boolean isPaused() {
        return false;
    }

    @Override
    public boolean isSmtEnabled() {
        return true;
    }

    @Override
    public long getTickDelayMs() {
        return 0L; // ludicrous mode
    }
}
