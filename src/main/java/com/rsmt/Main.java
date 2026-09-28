package com.rsmt;

import com.rsmt.core.*;
import com.rsmt.gui.MainApp;
import com.rsmt.sim.*;

import java.awt.Taskbar;
import java.awt.Toolkit;
import java.io.InputStream;
import java.util.List;

/**
 * rSMTv2 entry point.
 *
 * <ul>
 *   <li>No args → launches JavaFX GUI.</li>
 *   <li>8 args → CLI mode: {@code <numInst> <rSmtDelay> <%fxu> <%fpu> <%branch> <%lsu> <%rSmtAvail> <%depends>}</li>
 *   <li>12 args → CLI mode with latency overrides: append {@code <fxCycles> <fpCycles> <brCycles> <lsuCycles>}</li>
 * </ul>
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            setMacOsDockIcon();
            MainApp.launchGui(args);
            return;
        }

        if (args.length != 8 && args.length != 12) {
            System.err.println("ERROR: expected 8 or 12 arguments, or none.");
            System.err.println("Usage: java -jar rSMTv2.jar <numInst> <rSmtDelay> <%fxu> <%fpu> <%branch> <%lsu> <%rSmtAvail> <%depends> [<fxCycles> <fpCycles> <brCycles> <lsuCycles>]");
            System.exit(1);
        }

        try {
            int fxCycles  = args.length == 12 ? Integer.parseInt(args[8])  : InstructionGenerator.FX_CYCLES;
            int fpCycles  = args.length == 12 ? Integer.parseInt(args[9])  : InstructionGenerator.FP_CYCLES;
            int brCycles  = args.length == 12 ? Integer.parseInt(args[10]) : InstructionGenerator.BR_CYCLES;
            int lsuCycles = args.length == 12 ? Integer.parseInt(args[11]) : InstructionGenerator.LSU_CYCLES;
            SimulationConfig config = new SimulationConfig(
                    Integer.parseInt(args[0]),
                    Integer.parseInt(args[1]),
                    Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]),
                    Integer.parseInt(args[5]),
                    Integer.parseInt(args[6]),
                    Integer.parseInt(args[7]),
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

    /**
     * Sets the macOS Dock icon before JavaFX launches.
     * {@code java.awt.Taskbar} must be called on the main thread before
     * {@code Application.launch()} — this is the only reliable place to do it.
     * Silently ignored on non-macOS platforms or when the API is unavailable.
     */
    private static void setMacOsDockIcon() {
        try {
            if (!Taskbar.isTaskbarSupported()) return;
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return;
            InputStream is = Main.class.getResourceAsStream(
                    "/com/rsmt/gui/icon.png");
            if (is == null) return;
            java.awt.Image awtIcon = Toolkit.getDefaultToolkit().createImage(is.readAllBytes());
            taskbar.setIconImage(awtIcon);
        } catch (Exception ignored) {
            // Non-fatal — app still runs without Dock icon
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
