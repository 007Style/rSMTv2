package com.rsmt.core;

/**
 * Branch instruction. Latency: {@code 4} clocks.
 * Issues to the Branch slot and stalls fetch (control hazard) until retired.
 * Fully defined in Sub-Task 2.
 */
public record BranchInstruction(
        int order,
        int curClock,
        int doneClock
) implements Instruction {}
