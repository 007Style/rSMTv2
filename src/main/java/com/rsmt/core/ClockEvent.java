package com.rsmt.core;

/**
 * Immutable snapshot of a single clock tick — the data contract between
 * the simulation engine and the GUI pipeline visualization.
 *
 * <p>Slot name keys used in {@code slotSnapshot}:
 * {@code FXU0}, {@code FXU1}, {@code FPU0}, {@code FPU1}, {@code Branch}, {@code LSU}.
 * A {@code null} value for a key means the slot was idle that cycle.</p>
 *
 * <p>Fully defined in Sub-Task 2 (Core Data Model).</p>
 */
public record ClockEvent(
        int cycleNumber,
        java.util.Map<String, Instruction> slotSnapshot,
        double currentIpc,
        boolean smtActive
) {}
