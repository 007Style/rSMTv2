package com.rsmt.core;

import java.util.Map;

/**
 * Prints a {@link SimulationResult} to stdout in the rSMTv2 format.
 * The engine never prints — all output goes through this class.
 */
public final class SimulationReport {

    private SimulationReport() {}

    public static void print(SimulationResult r) {
        bar();
        System.out.printf("Total Cycles (rSMT ON)   : %d%n",  r.rCycles());
        System.out.printf("Total Cycles (rSMT OFF)  : %d%n",  r.normCycles());
        System.out.printf("Total Instructions       : %d%n",  r.totalInstructions());
        System.out.printf("Integer (FXU)            : %d%n",  r.intInstructions());
        System.out.printf("Integer rSMT (FXU1)      : %d%n",  r.smtInstructions());
        System.out.printf("FPU Instructions         : %d%n",  r.fpuInstructions());
        System.out.printf("LSU Instructions         : %d%n",  r.lsuInstructions());
        System.out.printf("IPC (rSMT ON)            : %.3f%n", r.ipc());
        bar();
        printUtilization(r.unitUtilization());
        bar();
        System.out.println("Pipeline Stalls (rSMT ON):");
        System.out.printf("  Structural : %d cycles%n", r.structuralStalls());
        System.out.printf("  Data       : %d cycles%n", r.dataStalls());
        System.out.printf("  Control    : %d cycles%n", r.controlStalls());
        bar();
        System.out.println("(4)**********************************************************");
        System.out.printf( "rSMT performance gain    : %.1f%%%n", r.performanceGain());
        System.out.println("(4)**********************************************************");
    }

    private static void printUtilization(Map<String, Double> util) {
        if (util.isEmpty()) return;
        System.out.println("Unit Utilization (rSMT ON):");
        for (Map.Entry<String, Double> e : util.entrySet()) {
            System.out.printf("  %-8s : %5.1f%%%n", e.getKey(), e.getValue());
        }
    }

    private static void bar() {
        System.out.println("**********************************************************");
    }
}
