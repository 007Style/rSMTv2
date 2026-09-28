package com.rsmt.core;

/**
 * Floating-point execution unit instruction (fadd, fsub, fmul, fdiv).
 * Latency: {@code 6} clocks. Routes to FPU slot 0 or FPU slot 1.
 * Fully defined in Sub-Task 2.
 */
public record FpuInstruction(
        int order,
        int curClock,
        int doneClock,
        double dop1,
        double dop2,
        int opIndex,      // 4=fadd, 5=fsub, 6=fmul, 7=fdiv
        double dresult
) implements Instruction {}
