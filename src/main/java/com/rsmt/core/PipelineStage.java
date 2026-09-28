package com.rsmt.core;

/**
 * The four stages of the rSMTv2 pipeline.
 * Used in {@link ClockEvent#pipelineSnapshot()} to show where each
 * instruction is this cycle for GUI visualization.
 */
public enum PipelineStage {
    FETCH,
    DECODE,
    ISSUE,
    EXECUTE
}
