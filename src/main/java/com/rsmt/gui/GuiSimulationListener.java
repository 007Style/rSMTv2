package com.rsmt.gui;

import com.rsmt.core.ClockEvent;
import com.rsmt.core.SimulationListener;
import com.rsmt.core.SimulationResult;
import javafx.application.Platform;

/**
 * {@link SimulationListener} that forwards engine events to the JavaFX Application Thread.
 *
 * <p>The engine calls these methods from a background {@code Task} thread.
 * We use {@link Platform#runLater} to safely update UI state.</p>
 */
public final class GuiSimulationListener implements SimulationListener {

    /** Functional interfaces for the controller to supply as lambdas. */
    @FunctionalInterface public interface TickHandler     { void onTick(ClockEvent e); }
    @FunctionalInterface public interface CompleteHandler { void onComplete(SimulationResult r); }

    private final TickHandler     tickHandler;
    private final CompleteHandler completeHandler;

    public GuiSimulationListener(TickHandler tickHandler, CompleteHandler completeHandler) {
        this.tickHandler     = tickHandler;
        this.completeHandler = completeHandler;
    }

    @Override
    public void onClockTick(ClockEvent event) {
        Platform.runLater(() -> tickHandler.onTick(event));
    }

    @Override
    public void onSimulationComplete(SimulationResult result) {
        Platform.runLater(() -> completeHandler.onComplete(result));
    }
}
