package com.rsmt.core;

import com.rsmt.sim.InstructionGenerator;

/**
 * Immutable simulation configuration — all parameters for one rSMTv2 run.
 *
 * <p>CLI arg order: {@code <numInstructions> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>}</p>
 *
 * @param numInstructions    total number of instructions to generate
 * @param rSmtDelay          extra clock penalty applied when FXU slot 1 retires (models inter-core latency)
 * @param percentInt         percentage (0–100) of instructions that are FXU (integer) ops
 * @param percentLoad        percentage (0–100) of non-integer instructions that are LSU (load/store) ops
 * @param rSmtAvailPercent   percentage (0–100) chance FXU slot 1 is available each cycle
 * @param rSmtDependsPercent percentage (0–100) chance a data hazard blocks FXU slot 1 issue
 * @param fxCycles           execution latency in cycles for FXU instructions (default 5)
 * @param fpCycles           execution latency in cycles for FPU instructions (default 6)
 * @param brCycles           execution latency in cycles for Branch instructions (default 4)
 * @param lsuCycles          execution latency in cycles for Load/Store instructions (default 3)
 */
public record SimulationConfig(
        int numInstructions,
        int rSmtDelay,
        int percentInt,
        int percentLoad,
        int rSmtAvailPercent,
        int rSmtDependsPercent,
        int fxCycles,
        int fpCycles,
        int brCycles,
        int lsuCycles
) {
    /** Default config matching the original rSMT defaults (plus 20% load). */
    public static final SimulationConfig DEFAULT = new SimulationConfig(
            100, 0, 50, 20, 50, 20,
            InstructionGenerator.FX_CYCLES,
            InstructionGenerator.FP_CYCLES,
            InstructionGenerator.BR_CYCLES,
            InstructionGenerator.LSU_CYCLES);

    public SimulationConfig {
        if (numInstructions <= 0) throw new IllegalArgumentException("numInstructions must be > 0");
        if (rSmtDelay < 0)        throw new IllegalArgumentException("rSmtDelay must be >= 0");
        checkPercent("percentInt",         percentInt);
        checkPercent("percentLoad",        percentLoad);
        checkPercent("rSmtAvailPercent",   rSmtAvailPercent);
        checkPercent("rSmtDependsPercent", rSmtDependsPercent);
        if (fxCycles  < 1) throw new IllegalArgumentException("fxCycles must be >= 1");
        if (fpCycles  < 1) throw new IllegalArgumentException("fpCycles must be >= 1");
        if (brCycles  < 1) throw new IllegalArgumentException("brCycles must be >= 1");
        if (lsuCycles < 1) throw new IllegalArgumentException("lsuCycles must be >= 1");
    }

    private static void checkPercent(String name, int value) {
        if (value < 0 || value > 100)
            throw new IllegalArgumentException(name + " must be 0–100, got: " + value);
    }
}
