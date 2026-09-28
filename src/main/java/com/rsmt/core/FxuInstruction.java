package com.rsmt.core;

/**
 * Fixed-point execution unit instruction (add, sub, mul, div).
 * Latency: {@code 5} clocks. Routes to FXU slot 0 (primary) or FXU slot 1 (rSMT).
 * Fully defined in Sub-Task 2.
 */
public record FxuInstruction(
        int order,
        int curClock,
        int doneClock,
        int op1,
        int op2,
        int opIndex,   // 0=add, 1=sub, 2=mul, 3=div
        int result
) implements Instruction {}
