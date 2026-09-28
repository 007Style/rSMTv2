package com.rsmt.core;

/**
 * Store instruction (memory write). Latency: {@code 3} clocks. Routes to LSU.
 * Fully defined in Sub-Task 2.
 */
public record StoreInstruction(
        int order,
        int curClock,
        int doneClock,
        int address,
        int value
) implements Instruction {}
