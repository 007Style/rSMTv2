package com.rsmt;

import com.rsmt.core.*;
import com.rsmt.sim.*;

import java.util.List;

/**
 * rSMTv2 entry point.
 *
 * <ul>
 *   <li>No args → launches JavaFX GUI (wired in ST-8).</li>
 *   <li>6 args → CLI mode: {@code <numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>}</li>
 * </ul>
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -jar rSMTv2.jar <numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>");
            System.out.println("       (no args) — JavaFX GUI (coming in ST-8)");
            System.out.println("Running default simulation: " + SimulationConfig.DEFAULT);
            runCli(SimulationConfig.DEFAULT);
            return;
        }

        if (args.length != 6) {
            System.err.println("ERROR: expected 6 arguments or none.");
            System.err.println("Usage: java -jar rSMTv2.jar <numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>");
            System.exit(1);
        }

        try {
            SimulationConfig config = new SimulationConfig(
                    Integer.parseInt(args[0]),
                    Integer.parseInt(args[1]),
                    Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]),
                    Integer.parseInt(args[5])
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
