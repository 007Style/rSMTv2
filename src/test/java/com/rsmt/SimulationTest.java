package com.rsmt;

import com.rsmt.core.*;
import com.rsmt.sim.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ST-2 (data model), ST-3 (generator), ST-4 (engine).
 */
class SimulationTest {

    private static final long SEED = 42L;

    // ─── ST-2: Data model ────────────────────────────────────────────────────

    @Test
    void simulationConfig_defaultIsValid() {
        assertDoesNotThrow(() -> SimulationConfig.DEFAULT);
        assertEquals(100, SimulationConfig.DEFAULT.numInstructions());
    }

    @Test
    void simulationConfig_rejectsInvalidPercent() {
        // percentFxu > 100
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(100, 0, 101, 0, 0, 0, 50, 20, 5, 6, 4, 3));
        // percentLsu negative
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(100, 0, 50, 0, 0, -1, 50, 20, 5, 6, 4, 3));
        // sum > 100
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(100, 0, 60, 20, 15, 10, 50, 20, 5, 6, 4, 3));
    }

    @Test
    void simulationConfig_percentNop() {
        SimulationConfig c = new SimulationConfig(100, 0, 50, 15, 10, 15, 50, 20, 5, 6, 4, 3);
        assertEquals(10, c.percentNop());
    }

    @Test
    void instructionTypes_correctLatency() {
        assertEquals(5, new FxuInstruction(0, 0, 5, 1, 2, 0, 0).doneClock());
        assertEquals(6, new FpuInstruction(0, 0, 6, 1.0, 2.0, 4, 0.0).doneClock());
        assertEquals(4, new BranchInstruction(0, 0, 4).doneClock());
        assertEquals(3, new LoadInstruction(0, 0, 3, 0).doneClock());
        assertEquals(3, new StoreInstruction(0, 0, 3, 0, 0).doneClock());
        assertEquals(0, new NopInstruction(0, 0, 0).doneClock());
    }

    // ─── ST-3: Instruction generator ────────────────────────────────────────

    @Test
    void generator_correctFxuRatio() {
        // 50% FXU, nothing else → ~50% FXU out of 1000
        SimulationConfig config = new SimulationConfig(1000, 0, 50, 0, 0, 0, 50, 20, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        assertEquals(1000, insts.size());
        long fxuCount = insts.stream().filter(i -> i instanceof FxuInstruction).count();
        assertTrue(fxuCount >= 400 && fxuCount <= 600,
                "Expected ~50% FXU, got: " + fxuCount);
    }

    @Test
    void generator_fpuInstructionsGenerated() {
        // 0% FXU, 100% FPU → all FPU
        SimulationConfig config = new SimulationConfig(200, 0, 0, 100, 0, 0, 50, 20, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        long fpuCount = insts.stream().filter(i -> i instanceof FpuInstruction).count();
        assertEquals(200, fpuCount, "All 200 instructions should be FPU");
    }

    @Test
    void generator_mixRatiosRespected() {
        // 40% FXU, 20% FPU, 15% Branch, 15% LSU → 10% NOP
        SimulationConfig config = new SimulationConfig(2000, 0, 40, 20, 15, 15, 50, 20, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        long fxu    = insts.stream().filter(i -> i instanceof FxuInstruction).count();
        long fpu    = insts.stream().filter(i -> i instanceof FpuInstruction).count();
        long branch = insts.stream().filter(i -> i instanceof BranchInstruction).count();
        long lsu    = insts.stream().filter(i -> i instanceof LoadInstruction
                                              || i instanceof StoreInstruction).count();

        // Allow ±10% tolerance
        assertTrue(fxu    >= 600 && fxu    <= 1000, "FXU ~40%, got: " + fxu);
        assertTrue(fpu    >= 300 && fpu    <= 700,  "FPU ~20%, got: " + fpu);
        assertTrue(branch >= 200 && branch <= 500,  "Branch ~15%, got: " + branch);
        assertTrue(lsu    >= 200 && lsu    <= 500,  "LSU ~15%, got: " + lsu);
    }

    @Test
    void generator_fpuArithmeticCorrect() {
        InstructionGenerator gen = new InstructionGenerator(SEED);
        // fadd: 4
        FpuInstruction fadd = new FpuInstruction(0, 0, 6, 2.0, 3.0, 4, 0.0);
        FpuInstruction result = (FpuInstruction) gen.execute(fadd);
        assertEquals(5.0, result.dresult(), 1e-10, "fadd should compute 2.0 + 3.0 = 5.0");

        // fsub: 5
        FpuInstruction fsub = new FpuInstruction(1, 0, 6, 7.0, 3.0, 5, 0.0);
        assertEquals(4.0, ((FpuInstruction) gen.execute(fsub)).dresult(), 1e-10);

        // fmul: 6
        FpuInstruction fmul = new FpuInstruction(2, 0, 6, 3.0, 4.0, 6, 0.0);
        assertEquals(12.0, ((FpuInstruction) gen.execute(fmul)).dresult(), 1e-10);

        // fdiv: 7
        FpuInstruction fdiv = new FpuInstruction(3, 0, 6, 9.0, 3.0, 7, 0.0);
        assertEquals(3.0, ((FpuInstruction) gen.execute(fdiv)).dresult(), 1e-10);
    }

    @Test
    void generator_fxuArithmeticCorrect() {
        InstructionGenerator gen = new InstructionGenerator(SEED);

        assertEquals(5,  ((FxuInstruction) gen.execute(new FxuInstruction(0, 0, 5, 2, 3, 0, 0))).result()); // add
        assertEquals(-1, ((FxuInstruction) gen.execute(new FxuInstruction(0, 0, 5, 2, 3, 1, 0))).result()); // sub
        assertEquals(6,  ((FxuInstruction) gen.execute(new FxuInstruction(0, 0, 5, 2, 3, 2, 0))).result()); // mul
        assertEquals(2,  ((FxuInstruction) gen.execute(new FxuInstruction(0, 0, 5, 6, 3, 3, 0))).result()); // div
    }

    @Test
    void generator_noDivisionByZero() {
        // div with op2=0 should not throw
        InstructionGenerator gen = new InstructionGenerator(SEED);
        FxuInstruction divByZero = new FxuInstruction(0, 0, 5, 10, 0, 3, 0);
        assertDoesNotThrow(() -> gen.execute(divByZero));
        assertEquals(0, ((FxuInstruction) gen.execute(divByZero)).result());
    }

    // ─── ST-4: Simulation engine ─────────────────────────────────────────────

    @Test
    void engine_smtOnFasterThanOff_atFullAvailability() {
        // 100% availability, 0% depends → rSMT should always fire → rCycles <= normCycles
        SimulationConfig config = new SimulationConfig(200, 0, 80, 0, 0, 0, 100, 0, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(), StaticSimulationControl.INSTANCE);
        SimulationResult result = engine.run(config, insts, gen);

        assertTrue(result.rCycles() <= result.normCycles(),
                "rSMT ON should be faster: rCycles=" + result.rCycles()
                        + " normCycles=" + result.normCycles());
        assertTrue(result.performanceGain() >= 100f,
                "Gain should be >= 100%, got: " + result.performanceGain());
    }

    @Test
    void engine_smtInstructionCount_nonZeroAtHighAvailability() {
        SimulationConfig config = new SimulationConfig(100, 0, 70, 0, 0, 0, 100, 0, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(), StaticSimulationControl.INSTANCE);
        SimulationResult result = engine.run(config, insts, gen);

        assertTrue(result.smtInstructions() > 0,
                "Expected rSMT instructions at 100% availability");
    }

    @Test
    void engine_listenerReceivesTickEvents() {
        SimulationConfig config = new SimulationConfig(20, 0, 50, 10, 10, 10, 50, 20, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        // Count how many tick events we receive
        int[] tickCount = {0};
        SimulationListener countingListener = new SimulationListener() {
            @Override public void onClockTick(ClockEvent e) { tickCount[0]++; }
            @Override public void onSimulationComplete(SimulationResult r) {}
        };

        SimulationEngine engine = new SimulationEngine(countingListener, StaticSimulationControl.INSTANCE);
        engine.run(config, insts, gen);

        assertTrue(tickCount[0] > 0, "Listener should have received tick events");
    }

    @Test
    void engine_dependsFixVerification() {
        // At 100% depends → slot 1 should always be blocked → smtInstructions == 0
        SimulationConfig config = new SimulationConfig(100, 0, 80, 0, 0, 0, 100, 100, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(), StaticSimulationControl.INSTANCE);
        SimulationResult result = engine.run(config, insts, gen);

        assertEquals(0, result.smtInstructions(),
                "At 100% depends, no rSMT instructions should issue (hazard always present)");
    }

    @Test
    void engine_performanceGainCalculation() {
        SimulationConfig config = SimulationConfig.DEFAULT;
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(), StaticSimulationControl.INSTANCE);
        SimulationResult result = engine.run(config, insts, gen);

        float expectedGain = ((float) result.normCycles() / (float) result.rCycles()) * 100f;
        assertEquals(expectedGain, result.performanceGain(), 0.01f);
    }

    // ─── ST-7: Pipeline stall tests ──────────────────────────────────────────

    @Test
    void pipeline_controlStalls_nonZeroWithBranches() {
        SimulationConfig config = new SimulationConfig(50, 0, 0, 0, 0, 0, 50, 20, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        // Force a stream with branches
        List<Instruction> insts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            insts.add(new BranchInstruction(i, 0, 4));
        }

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(), StaticSimulationControl.INSTANCE);
        SimulationResult result = engine.run(config, insts, gen);

        assertTrue(result.controlStalls() > 0,
                "Branch-heavy stream should produce control stalls");
    }

    @Test
    void pipeline_dataStalls_nonZeroAtHighDependsPercent() {
        // 100% depends → every rSMT issue attempt is blocked by data hazard
        SimulationConfig config = new SimulationConfig(100, 0, 80, 0, 0, 0, 100, 100, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(), StaticSimulationControl.INSTANCE);
        SimulationResult result = engine.run(config, insts, gen);

        assertTrue(result.dataStalls() > 0,
                "100% depends should produce data stalls");
    }

    @Test
    void pipeline_ipcWithinExpectedRange() {
        SimulationConfig config = new SimulationConfig(200, 0, 60, 10, 10, 10, 80, 10, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        SimulationEngine engine = new SimulationEngine(
                new NoOpSimulationListener(), StaticSimulationControl.INSTANCE);
        SimulationResult result = engine.run(config, insts, gen);

        // IPC must be > 0 and reasonable (single-issue pipeline ≤ ~1.5 with rSMT)
        assertTrue(result.ipc() > 0.0, "IPC must be positive");
        assertTrue(result.ipc() <= 2.0, "IPC should not exceed 2.0 for this pipeline model");
    }

    @Test
    void pipeline_pipelineSnapshotContainsAllStages() {
        SimulationConfig config = new SimulationConfig(10, 0, 50, 10, 10, 10, 50, 20, 5, 6, 4, 3);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        boolean[] sawAllStages = {false};
        SimulationListener stageChecker = new SimulationListener() {
            @Override public void onClockTick(ClockEvent e) {
                if (e.pipelineSnapshot() != null
                        && e.pipelineSnapshot().containsKey(PipelineStage.FETCH)
                        && e.pipelineSnapshot().containsKey(PipelineStage.DECODE)
                        && e.pipelineSnapshot().containsKey(PipelineStage.EXECUTE)) {
                    sawAllStages[0] = true;
                }
            }
            @Override public void onSimulationComplete(SimulationResult r) {}
        };

        SimulationEngine engine = new SimulationEngine(stageChecker, StaticSimulationControl.INSTANCE);
        engine.run(config, insts, gen);

        assertTrue(sawAllStages[0], "ClockEvent should contain all pipeline stage keys");
    }
}
