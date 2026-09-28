package com.rsmt.core;

/**
 * Controls the simulation engine at runtime.
 * Read by the engine on every clock tick — implementations must be thread-safe.
 *
 * <p>The CLI uses {@link StaticSimulationControl} (always-on, no delay, not paused).
 * The GUI provides an implementation backed by live UI controls.</p>
 *
 * <p>Animation speed mapping:
 * <ul>
 *   <li>1s per tick  → 1000 ms</li>
 *   <li>2s per tick  → 2000 ms</li>
 *   <li>5s per tick  → 5000 ms</li>
 *   <li>10s per tick → 10000 ms</li>
 *   <li>Ludicrous    → 0 ms (no sleep)</li>
 * </ul>
 * </p>
 */
public interface SimulationControl {

    /**
     * Returns {@code true} if the engine should pause (spin-wait) this tick.
     */
    boolean isPaused();

    /**
     * Returns {@code true} if rSMT dual-issue (FXU slot 1) is currently enabled.
     * Checked every tick — can be toggled mid-simulation.
     */
    boolean isSmtEnabled();

    /**
     * Returns the number of milliseconds the engine should sleep after each clock tick.
     * Return {@code 0} for ludicrous (maximum) speed.
     */
    long getTickDelayMs();
}
