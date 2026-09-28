package com.rsmt.core;

/**
 * Immutable simulation configuration — all parameters for one rSMTv2 run.
 *
 * <p>CLI arg order: {@code <numInstructions> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>}</p>
 *
 * @param numInstructions  total number of instructions to generate
 * @param rSmtDelay        extra clock penalty applied when FXU slot 1 retires (models inter-core latency)
 * @param percentInt       percentage (0–100) of instructions that are FXU (integer) ops
 * @param percentLoad      percentage (0–100) of non-integer instructions that are LSU (load/store) ops
 * @param rSmtAvailPercent percentage (0–100) chance FXU slot 1 is available each cycle
 * @param rSmtDependsPercent percentage (0–100) chance a data hazard blocks FXU slot 1 issue
 */
public record SimulationConfig(
        int numInstructions,
        int rSmtDelay,
        int percentInt,
        int percentLoad,
        int rSmtAvailPercent,
        int rSmtDependsPercent
) {
    /** Default config matching the original rSMT defaults (plus 20% load). */
    public static final SimulationConfig DEFAULT =
            new SimulationConfig(100, 0, 50, 20, 50, 20);

    public SimulationConfig {
        if (numInstructions <= 0) throw new IllegalArgumentException("numInstructions must be > 0");
        if (rSmtDelay < 0)        throw new IllegalArgumentException("rSmtDelay must be >= 0");
        checkPercent("percentInt",         percentInt);
        checkPercent("percentLoad",        percentLoad);
        checkPercent("rSmtAvailPercent",   rSmtAvailPercent);
        checkPercent("rSmtDependsPercent", rSmtDependsPercent);
    }

    private static void checkPercent(String name, int value) {
        if (value < 0 || value > 100)
            throw new IllegalArgumentException(name + " must be 0–100, got: " + value);
    }
}
