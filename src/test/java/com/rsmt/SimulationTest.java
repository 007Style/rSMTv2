package com.rsmt;

import com.rsmt.core.*;
import com.rsmt.sim.*;
import org.junit.jupiter.api.Test;

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
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(100, 0, 101, 20, 50, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(100, 0, 50, -1, 50, 20));
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
        SimulationConfig config = new SimulationConfig(1000, 0, 50, 0, 50, 20);
        InstructionGenerator gen = new InstructionGenerator(SEED);
        List<Instruction> insts = gen.generate(config);

        assertEquals(1000, insts.size());

        long fxuCount = insts.stream().filter(i -> i instanceof FxuInstruction).count();
        // With 50% int target, expect roughly 400–600 FXU instructions out of 1000
        assertTrue(fxuCount >= 350 && fxuCount <= 650,
                "Expected ~50% FXU, got: " + fxuCount);
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
        SimulationConfig config = new SimulationConfig(200, 0, 80, 0, 100, 0);
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
        SimulationConfig config = new SimulationConfig(100, 0, 70, 0, 100, 0);
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
        SimulationConfig config = new SimulationConfig(20, 0, 50, 0, 50, 20);
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
        SimulationConfig config = new SimulationConfig(100, 0, 80, 0, 100, 100);
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
}
