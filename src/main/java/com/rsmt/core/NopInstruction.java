package com.rsmt.core;

/**
 * No-operation instruction. Latency: {@code 0} clocks — retires immediately on issue.
 * Fully defined in Sub-Task 2.
 */
public record NopInstruction(
        int order,
        int curClock,
        int doneClock
) implements Instruction {}
