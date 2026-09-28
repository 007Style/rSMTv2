package com.rsmt.core;

import com.rsmt.sim.InstructionGenerator;

/**
 * Immutable simulation configuration — all parameters for one rSMTv2 run.
 *
 * <h3>Workload mix</h3>
 * <p>{@code percentFxu + percentFpu + percentBranch + percentLsu} must be ≤ 100.
 * The remainder (100 − sum) becomes NOP instructions.</p>
 *
 * @param numInstructions    total number of instructions to generate
 * @param rSmtDelay          extra clock penalty applied when FXU slot 1 retires
 * @param percentFxu         percentage (0–100) of instructions that are FXU integer ops
 * @param percentFpu         percentage (0–100) of instructions that are FPU floating-point ops
 * @param percentBranch      percentage (0–100) of instructions that are Branch ops
 * @param percentLsu         percentage (0–100) of instructions that are Load/Store ops
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
        int percentFxu,
        int percentFpu,
        int percentBranch,
        int percentLsu,
        int rSmtAvailPercent,
        int rSmtDependsPercent,
        int fxCycles,
        int fpCycles,
        int brCycles,
        int lsuCycles
) {
    /** Default config: 50% FXU, 15% FPU, 10% Branch, 15% LSU, 10% NOP. */
    public static final SimulationConfig DEFAULT = new SimulationConfig(
            100, 0, 50, 15, 10, 15, 50, 20,
            InstructionGenerator.FX_CYCLES,
            InstructionGenerator.FP_CYCLES,
            InstructionGenerator.BR_CYCLES,
            InstructionGenerator.LSU_CYCLES);

    public SimulationConfig {
        if (numInstructions <= 0) throw new IllegalArgumentException("numInstructions must be > 0");
        if (rSmtDelay < 0)        throw new IllegalArgumentException("rSmtDelay must be >= 0");
        checkPercent("percentFxu",         percentFxu);
        checkPercent("percentFpu",         percentFpu);
        checkPercent("percentBranch",      percentBranch);
        checkPercent("percentLsu",         percentLsu);
        checkPercent("rSmtAvailPercent",   rSmtAvailPercent);
        checkPercent("rSmtDependsPercent", rSmtDependsPercent);
        int sum = percentFxu + percentFpu + percentBranch + percentLsu;
        if (sum > 100)
            throw new IllegalArgumentException(
                    "percentFxu+percentFpu+percentBranch+percentLsu must be <= 100, got: " + sum);
        if (fxCycles  < 1) throw new IllegalArgumentException("fxCycles must be >= 1");
        if (fpCycles  < 1) throw new IllegalArgumentException("fpCycles must be >= 1");
        if (brCycles  < 1) throw new IllegalArgumentException("brCycles must be >= 1");
        if (lsuCycles < 1) throw new IllegalArgumentException("lsuCycles must be >= 1");
    }

    /** Percentage of instructions that will be NOP (100 − sum of the four typed slots). */
    public int percentNop() {
        return 100 - percentFxu - percentFpu - percentBranch - percentLsu;
    }

    private static void checkPercent(String name, int value) {
        if (value < 0 || value > 100)
            throw new IllegalArgumentException(name + " must be 0–100, got: " + value);
    }
}
