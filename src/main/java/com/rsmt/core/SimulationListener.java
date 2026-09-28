package com.rsmt.core;

/**
 * Receives events from the simulation engine on every clock tick.
 * The CLI uses {@link NoOpSimulationListener}. The GUI provides a
 * live implementation that posts updates to the JavaFX Application Thread.
 *
 * <p>Methods are called from the engine's background thread — implementations
 * must be thread-safe (e.g. use {@code Platform.runLater} in JavaFX).</p>
 */
public interface SimulationListener {

    /**
     * Called once per clock cycle with a snapshot of pipeline slot state.
     *
     * @param event immutable snapshot of this clock tick
     */
    void onClockTick(ClockEvent event);

    /**
     * Called when the simulation run completes (both SMT-ON and SMT-OFF passes).
     *
     * @param result final simulation metrics
     */
    void onSimulationComplete(SimulationResult result);
}
