package com.rsmt.core;

import java.util.Map;

/**
 * Immutable simulation result — returned by {@code SimulationEngine.run()}.
 * Fully populated in Sub-Task 4+; fields expanded in Sub-Tasks 6 and 7.
 */
public record SimulationResult(
        int rCycles,
        int normCycles,
        float performanceGain,
        int totalInstructions,
        int intInstructions,
        int smtInstructions,
        int fpuInstructions,
        int lsuInstructions,
        double ipc,
        Map<String, Double> unitUtilization,  // slot name → % busy
        int structuralStalls,
        int dataStalls,
        int controlStalls
) {}
