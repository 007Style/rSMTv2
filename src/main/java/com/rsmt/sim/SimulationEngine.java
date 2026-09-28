package com.rsmt.sim;

import com.rsmt.core.*;

import java.util.*;

/**
 * Core simulation engine for rSMTv2.
 *
 * <p>Runs two passes over the same instruction stream — SMT-ON and SMT-OFF — and
 * returns a {@link SimulationResult} with cycle counts, utilization, stall counts,
 * and IPC.</p>
 *
 * <p>Internally delegates to {@link PipelineEngine} for the 4-stage
 * Fetch → Decode → Issue → Execute/Retire loop. Callers only use this class.</p>
 *
 * <p>Every clock tick:
 * <ul>
 *   <li>Fires {@link SimulationListener#onClockTick(ClockEvent)} with a pipeline snapshot.</li>
 *   <li>Checks {@link SimulationControl#isPaused()} and spin-waits if paused.</li>
 *   <li>Checks {@link SimulationControl#isSmtEnabled()} to decide whether to fill FXU slot 1.</li>
 *   <li>Sleeps {@link SimulationControl#getTickDelayMs()} ms for animation pacing.</li>
 * </ul>
 * </p>
 *
 * <h3>Bug fixes vs original {@code rSMT.java}</h3>
 * <ul>
 *   <li>{@code rSMT_depends()} was inverted — returned {@code false} (no hazard) when
 *       {@code rGen <= dependsPercent}. Fixed: returns {@code true} (hazard present, block issue).</li>
 *   <li>Raw {@code Vector} replaced with {@code ArrayDeque<Instruction>}.</li>
 *   <li>Constructor no longer does everything — engine is testable and reusable.</li>
 * </ul>
 */
public final class SimulationEngine {

    private final SimulationListener listener;
    private final SimulationControl  control;
    private final Random             rng = new Random();

    /** Slot name constants — must match AGENTS.md and GUI key expectations. */
    public static final String SLOT_FXU0   = "FXU0";
    public static final String SLOT_FXU1   = "FXU1";
    public static final String SLOT_FPU0   = "FPU0";
    public static final String SLOT_FPU1   = "FPU1";
    public static final String SLOT_BRANCH = "Branch";
    public static final String SLOT_LSU    = "LSU";

    private static final List<String> ALL_SLOTS =
            List.of(SLOT_FXU0, SLOT_FXU1, SLOT_FPU0, SLOT_FPU1, SLOT_BRANCH, SLOT_LSU);

    public SimulationEngine(SimulationListener listener, SimulationControl control) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.control  = Objects.requireNonNull(control,  "control");
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Runs both SMT-ON and SMT-OFF passes and returns the combined result.
     * The listener receives tick events for both passes.
     *
     * @param config       simulation parameters
     * @param instructions instruction stream (same list used for both passes; not mutated)
     * @param generator    used for {@code execute()} and {@code instName()}
     */
    public SimulationResult run(SimulationConfig config,
                                List<Instruction> instructions,
                                InstructionGenerator generator) {

        PipelineEngine pipeline = new PipelineEngine(listener, control, generator, rng);

        System.out.println("(2) Simulating rSMT Activated... START");
        PipelineEngine.PipelinePassResult smtOn = pipeline.run(config, instructions, true);
        System.out.println("(2) Simulating rSMT Activated... FINISHED");

        System.out.println("(3) Simulating rSMT Deactivated... START");
        PipelineEngine.PipelinePassResult smtOff = pipeline.run(config, instructions, false);
        System.out.println("(3) Simulating rSMT Deactivated... FINISHED");

        float gain = smtOff.cycles() > 0
                ? ((float) smtOff.cycles() / (float) smtOn.cycles()) * 100f
                : 0f;

        double ipc = smtOn.cycles() > 0
                ? (double) instructions.size() / smtOn.cycles()
                : 0.0;

        SimulationResult result = new SimulationResult(
                smtOn.cycles(),
                smtOff.cycles(),
                gain,
                instructions.size(),
                smtOn.intInstructions(),
                smtOn.smtInstructions(),
                smtOn.fpuInstructions(),
                smtOn.lsuInstructions(),
                ipc,
                smtOn.unitUtilization(smtOn.cycles()),
                smtOn.structuralStalls(),
                smtOn.dataStalls(),
                smtOn.controlStalls()
        );

        listener.onSimulationComplete(result);
        return result;
    }
}
