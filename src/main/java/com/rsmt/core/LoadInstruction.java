package com.rsmt.core;

/**
 * Load instruction (memory read). Latency: {@code 3} clocks. Routes to LSU.
 * Fully defined in Sub-Task 2.
 */
public record LoadInstruction(
        int order,
        int curClock,
        int doneClock,
        int address
) implements Instruction {}
