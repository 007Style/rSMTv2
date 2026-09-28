package com.rsmt.core;

/**
 * Sealed instruction interface.
 * Permitted subtypes: {@code FxuInstruction}, {@code FpuInstruction},
 * {@code BranchInstruction}, {@code LoadInstruction}, {@code StoreInstruction},
 * {@code NopInstruction}.
 *
 * <p>Fully defined in Sub-Task 2 (Core Data Model). This stub exists so
 * {@link ClockEvent} and {@link SimulationListener} compile in Sub-Task 1.</p>
 */
public sealed interface Instruction
        permits FxuInstruction, FpuInstruction, BranchInstruction,
                LoadInstruction, StoreInstruction, NopInstruction {

    /** Instruction order index in the original stream (0-based). */
    int order();

    /** Clock cycle at which this instruction finishes executing. */
    int doneClock();

    /** Current clock progress counter. */
    int curClock();
}
