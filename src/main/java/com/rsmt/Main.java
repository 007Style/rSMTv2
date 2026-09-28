package com.rsmt;

import com.rsmt.core.*;
import com.rsmt.gui.MainApp;
import com.rsmt.sim.*;

import java.util.List;

/**
 * rSMTv2 entry point.
 *
 * <ul>
 *   <li>No args → launches JavaFX GUI.</li>
 *   <li>6 args → CLI mode: {@code <numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>}</li>
 * </ul>
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            MainApp.launchGui(args);
            return;
        }

        if (args.length != 6 && args.length != 10) {
            System.err.println("ERROR: expected 6 or 10 arguments, or none.");
            System.err.println("Usage: java -jar rSMTv2.jar <numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends> [<fxCycles> <fpCycles> <brCycles> <lsuCycles>]");
            System.exit(1);
        }

        try {
            int fxCycles  = args.length == 10 ? Integer.parseInt(args[6]) : InstructionGenerator.FX_CYCLES;
            int fpCycles  = args.length == 10 ? Integer.parseInt(args[7]) : InstructionGenerator.FP_CYCLES;
            int brCycles  = args.length == 10 ? Integer.parseInt(args[8]) : InstructionGenerator.BR_CYCLES;
            int lsuCycles = args.length == 10 ? Integer.parseInt(args[9]) : InstructionGenerator.LSU_CYCLES;
            SimulationConfig config = new SimulationConfig(
                    Integer.parseInt(args[0]),
                    Integer.parseInt(args[1]),
                    Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]),
                    Integer.parseInt(args[5]),
                    fxCycles, fpCycles, brCycles, lsuCycles
            );
            runCli(config);
        } catch (NumberFormatException e) {
            System.err.println("ERROR: all arguments must be integers. " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: invalid argument: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runCli(SimulationConfig config) {
        long seed = System.currentTimeMillis();
        InstructionGenerator gen = new InstructionGenerator(seed);
        List<Instruction> instructions = gen.generate(config);

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(),
                StaticSimulationControl.INSTANCE
        );

        SimulationResult result = engine.run(config, instructions, gen);
        SimulationReport.print(result);
    }
}
