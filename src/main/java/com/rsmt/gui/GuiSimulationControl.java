package com.rsmt.gui;

import com.rsmt.core.SimulationControl;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe {@link SimulationControl} backed by live JavaFX UI controls.
 *
 * <p>The engine reads this from a background thread; JavaFX controls write it
 * from the Application Thread. All fields use atomics — no synchronization needed.</p>
 *
 * <p>Speed presets (ms per tick):
 * <ul>
 *   <li>10s = 10000 ms</li>
 *   <li>5s  = 5000 ms</li>
 *   <li>2s  = 2000 ms</li>
 *   <li>1s  = 1000 ms</li>
 *   <li>Ludicrous = 0 ms</li>
 * </ul>
 * </p>
 */
public final class GuiSimulationControl implements SimulationControl {

    private final AtomicBoolean paused     = new AtomicBoolean(false);
    private final AtomicBoolean smtEnabled = new AtomicBoolean(true);
    private final AtomicLong    tickDelayMs = new AtomicLong(500L);

    @Override public boolean isPaused()        { return paused.get(); }
    @Override public boolean isSmtEnabled()    { return smtEnabled.get(); }
    @Override public long    getTickDelayMs()  { return tickDelayMs.get(); }

    public void setPaused(boolean v)       { paused.set(v); }
    public void setSmtEnabled(boolean v)   { smtEnabled.set(v); }
    public void setTickDelayMs(long ms)    { tickDelayMs.set(ms); }
}
