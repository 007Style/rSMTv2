package com.rsmt.core;

/**
 * No-op {@link SimulationListener} for CLI and headless test use.
 * Discards all tick events and completion notifications.
 */
public final class NoOpSimulationListener implements SimulationListener {

    @Override
    public void onClockTick(ClockEvent event) {
        // intentionally empty
    }

    @Override
    public void onSimulationComplete(SimulationResult result) {
        // intentionally empty
    }
}
