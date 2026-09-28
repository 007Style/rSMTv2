package com.rsmt.core;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a single clock tick — the data contract between
 * the simulation engine and the GUI pipeline visualization.
 *
 * <p>Slot name keys used in {@code slotSnapshot}:
 * {@code FXU0}, {@code FXU1}, {@code FPU0}, {@code FPU1}, {@code Branch}, {@code LSU}.
 * A {@code null} value for a key means the slot was idle that cycle.</p>
 *
 * <p>{@code pipelineSnapshot} maps each {@link PipelineStage} to the list of
 * instructions currently in that stage. Empty list means the stage is idle.
 * Used by the GUI to render the Fetch/Decode/Issue/Execute lane view.</p>
 *
 * <p>Stall flags: {@code structuralStall} — a needed execution unit was full;
 * {@code dataStall} — a data hazard blocked FXU slot 1 issue;
 * {@code controlStall} — a branch is in-flight, fetch is paused.</p>
 */
public record ClockEvent(
        int cycleNumber,
        Map<String, Instruction> slotSnapshot,
        Map<PipelineStage, List<Instruction>> pipelineSnapshot,
        double currentIpc,
        boolean smtActive,
        boolean structuralStall,
        boolean dataStall,
        boolean controlStall
) {}
