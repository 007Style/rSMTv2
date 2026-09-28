package com.rsmt.sim;

import com.rsmt.core.*;

import java.util.*;

/**
 * Core simulation engine for rSMTv2.
 *
 * <p>Runs two passes over the same instruction stream — SMT-ON and SMT-OFF — and
 * returns a {@link SimulationResult} with cycle counts, utilization, stall counts,
 * and IPC.</p>
 *
 * <p>Every clock tick:
 * <ul>
 *   <li>Fires {@link SimulationListener#onClockTick(ClockEvent)} with a pipeline snapshot.</li>
 *   <li>Checks {@link SimulationControl#isPaused()} and spin-waits if paused.</li>
 *   <li>Checks {@link SimulationControl#isSmtEnabled()} to decide whether to fill FXU slot 1.</li>
 *   <li>Sleeps {@link SimulationControl#getTickDelayMs()} ms for animation pacing.</li>
 * </ul>
 * </p>
 *
 * <h3>Bug fixes vs original {@code rSMT.java}</h3>
 * <ul>
 *   <li>{@code rSMT_depends()} was inverted — returned {@code false} (no hazard) when
 *       {@code rGen <= dependsPercent}. Fixed: {@link #rSmtDepends()} returns {@code true}
 *       (hazard present, block issue) when {@code rGen <= dependsPercent}.</li>
 *   <li>Raw {@code Vector} replaced with {@code ArrayDeque<Instruction>}.</li>
 *   <li>Constructor no longer does everything — engine is testable and reusable.</li>
 * </ul>
 */
public final class SimulationEngine {

    private final SimulationListener listener;
    private final SimulationControl  control;
    private final Random             rng = new Random();

    /** Slot name constants — must match AGENTS.md and GUI key expectations. */
    public static final String SLOT_FXU0   = "FXU0";
    public static final String SLOT_FXU1   = "FXU1";
    public static final String SLOT_FPU0   = "FPU0";
    public static final String SLOT_FPU1   = "FPU1";
    public static final String SLOT_BRANCH = "Branch";
    public static final String SLOT_LSU    = "LSU";

    private static final List<String> ALL_SLOTS =
            List.of(SLOT_FXU0, SLOT_FXU1, SLOT_FPU0, SLOT_FPU1, SLOT_BRANCH, SLOT_LSU);

    public SimulationEngine(SimulationListener listener, SimulationControl control) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.control  = Objects.requireNonNull(control,  "control");
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Runs both SMT-ON and SMT-OFF passes and returns the combined result.
     * The listener receives tick events for both passes.
     *
     * @param config       simulation parameters
     * @param instructions instruction stream (same list used for both passes; not mutated)
     * @param generator    used for {@code execute()} and {@code instName()}
     */
    public SimulationResult run(SimulationConfig config,
                                List<Instruction> instructions,
                                InstructionGenerator generator) {

        System.out.println("(2) Simulating rSMT Activated... START");
        PassResult smtOn = runPass(config, instructions, generator, true);
        System.out.println("(2) Simulating rSMT Activated... FINISHED");

        System.out.println("(3) Simulating rSMT Deactivated... START");
        PassResult smtOff = runPass(config, instructions, generator, false);
        System.out.println("(3) Simulating rSMT Deactivated... FINISHED");

        float gain = smtOff.cycles > 0
                ? ((float) smtOff.cycles / (float) smtOn.cycles) * 100f
                : 0f;

        double ipc = smtOn.cycles > 0
                ? (double) instructions.size() / smtOn.cycles
                : 0.0;

        SimulationResult result = new SimulationResult(
                smtOn.cycles,
                smtOff.cycles,
                gain,
                instructions.size(),
                smtOn.intInstructions,
                smtOn.smtInstructions,
                smtOn.fpuInstructions,
                smtOn.lsuInstructions,
                ipc,
                smtOn.unitUtilization(smtOn.cycles),
                0, 0, 0  // stall tracking added in ST-7 (PipelineEngine)
        );

        listener.onSimulationComplete(result);
        return result;
    }

    // ─── Pass execution ───────────────────────────────────────────────────────

    private PassResult runPass(SimulationConfig config,
                               List<Instruction> instructions,
                               InstructionGenerator generator,
                               boolean smtEnabledDefault) {

        // Use ArrayDeque as the issue queue — fast head removal
        ArrayDeque<Instruction> queue = new ArrayDeque<>(instructions);

        // Pipeline slots (null = idle)
        Instruction[] fxu    = new Instruction[2]; // [0]=primary, [1]=SMT
        Instruction[] fpu    = new Instruction[2]; // [0],[1]
        Instruction[] branch = new Instruction[1];
        Instruction[] lsu    = new Instruction[1];

        int clock          = 0;
        int intInst        = 0;
        int smtInst        = 0;
        int fpuInst        = 0;
        int lsuInst        = 0;
        boolean execBranch = false;

        // Per-unit busy-cycle counters
        int busyFxu0 = 0, busyFxu1 = 0, busyFpu0 = 0, busyFpu1 = 0,
                busyBranch = 0, busyLsu = 0;

        while (!queue.isEmpty()
                || fxu[0] != null || fxu[1] != null
                || fpu[0] != null || fpu[1] != null
                || branch[0] != null || lsu[0] != null) {

            clock++;

            // ── Check control every tick ──────────────────────────────────
            handlePauseAndDelay();
            boolean smtActive = smtEnabledDefault && control.isSmtEnabled();

            // ── ISSUE stage ───────────────────────────────────────────────
            if (!queue.isEmpty() && !execBranch) {
                Instruction head = queue.peek();

                if (fxu[0] == null && head instanceof FxuInstruction) {
                    fxu[0] = queue.poll();
                    intInst++;

                } else if (fxu[1] == null && smtActive
                        && rSmtCycle(config) && !rSmtDepends(config)
                        && !queue.isEmpty() && queue.peek() instanceof FxuInstruction) {
                    fxu[1] = queue.poll();
                    intInst++;
                    smtInst++;
                    System.out.println("CYCLE: " + clock + "  rSMT instruction: "
                            + generator.instName(fxu[1]));

                } else if (fpu[0] == null && head instanceof FpuInstruction) {
                    fpu[0] = queue.poll();
                    fpuInst++;

                } else if (fpu[1] == null && smtActive
                        && !queue.isEmpty() && queue.peek() instanceof FpuInstruction) {
                    fpu[1] = queue.poll();
                    fpuInst++;

                } else if (branch[0] == null && head instanceof BranchInstruction) {
                    branch[0] = queue.poll();
                    execBranch = true;

                } else if (lsu[0] == null
                        && (head instanceof LoadInstruction || head instanceof StoreInstruction)) {
                    lsu[0] = queue.poll();
                    lsuInst++;

                } else if (head instanceof NopInstruction) {
                    queue.poll(); // NOP retires immediately — 0 clocks
                }
            }

            // ── EXECUTE / RETIRE stage ────────────────────────────────────
            fxu[0]    = tick(fxu[0],    generator);
            fxu[1]    = tickWithDelay(fxu[1], generator, config.rSmtDelay(), clock);
            fpu[0]    = tick(fpu[0],    generator);
            fpu[1]    = tick(fpu[1],    generator);
            lsu[0]    = tick(lsu[0],    generator);
            if (branch[0] != null) {
                branch[0] = tick(branch[0], generator);
                if (branch[0] == null) execBranch = false; // branch retired
            }

            // ── Count busy slots this cycle ───────────────────────────────
            if (fxu[0]    != null) busyFxu0++;
            if (fxu[1]    != null) busyFxu1++;
            if (fpu[0]    != null) busyFpu0++;
            if (fpu[1]    != null) busyFpu1++;
            if (branch[0] != null) busyBranch++;
            if (lsu[0]    != null) busyLsu++;

            // ── Fire tick event ───────────────────────────────────────────
            Map<String, Instruction> snapshot = buildSnapshot(fxu, fpu, branch, lsu);
            double currentIpc = clock > 0
                    ? (double)(instructions.size() - queue.size()) / clock
                    : 0.0;
            listener.onClockTick(new ClockEvent(clock, snapshot, currentIpc, smtActive));
        }

        return new PassResult(clock, intInst, smtInst, fpuInst, lsuInst,
                busyFxu0, busyFxu1, busyFpu0, busyFpu1, busyBranch, busyLsu);
    }

    // ─── Tick helpers ─────────────────────────────────────────────────────────

    /**
     * Advances an instruction's clock by 1. Returns {@code null} when it retires
     * (curClock reaches doneClock), otherwise returns the instruction with curClock+1.
     */
    private Instruction tick(Instruction inst, InstructionGenerator gen) {
        if (inst == null) return null;
        if (getCurClock(inst) >= getDoneClock(inst)) {
            gen.execute(inst); // side-effect: compute result (ignored in cycle sim)
            return null;       // retired
        }
        return advanceClock(inst);
    }

    /** Like {@link #tick} but adds {@code delay} extra cycles before retiring. */
    private Instruction tickWithDelay(Instruction inst, InstructionGenerator gen,
                                      int delay, int clock) {
        if (inst == null) return null;
        if (getCurClock(inst) >= getDoneClock(inst) + delay) {
            gen.execute(inst);
            return null;
        }
        return advanceClock(inst);
    }

    // ─── Clock field accessors (records are immutable — build new instance) ──

    private static int getCurClock(Instruction inst) {
        return switch (inst) {
            case FxuInstruction    i -> i.curClock();
            case FpuInstruction    i -> i.curClock();
            case BranchInstruction i -> i.curClock();
            case LoadInstruction   i -> i.curClock();
            case StoreInstruction  i -> i.curClock();
            case NopInstruction    i -> i.curClock();
        };
    }

    private static int getDoneClock(Instruction inst) {
        return switch (inst) {
            case FxuInstruction    i -> i.doneClock();
            case FpuInstruction    i -> i.doneClock();
            case BranchInstruction i -> i.doneClock();
            case LoadInstruction   i -> i.doneClock();
            case StoreInstruction  i -> i.doneClock();
            case NopInstruction    i -> i.doneClock();
        };
    }

    private static Instruction advanceClock(Instruction inst) {
        return switch (inst) {
            case FxuInstruction    i -> new FxuInstruction(i.order(), i.curClock()+1, i.doneClock(),
                    i.op1(), i.op2(), i.opIndex(), i.result());
            case FpuInstruction    i -> new FpuInstruction(i.order(), i.curClock()+1, i.doneClock(),
                    i.dop1(), i.dop2(), i.opIndex(), i.dresult());
            case BranchInstruction i -> new BranchInstruction(i.order(), i.curClock()+1, i.doneClock());
            case LoadInstruction   i -> new LoadInstruction(i.order(), i.curClock()+1, i.doneClock(), i.address());
            case StoreInstruction  i -> new StoreInstruction(i.order(), i.curClock()+1, i.doneClock(), i.address(), i.value());
            case NopInstruction    i -> new NopInstruction(i.order(), i.curClock()+1, i.doneClock());
        };
    }

    // ─── rSMT probability checks ──────────────────────────────────────────────

    /**
     * Returns {@code true} if the rSMT slot is available this cycle.
     * Probability = {@code rSmtAvailPercent / 100}.
     */
    private boolean rSmtCycle(SimulationConfig config) {
        return rng.nextInt(101) <= config.rSmtAvailPercent();
    }

    /**
     * Returns {@code true} if a data hazard blocks FXU slot 1 this cycle.
     * Probability = {@code rSmtDependsPercent / 100}.
     *
     * <p><b>Bug fix</b>: the original {@code rSMT_depends()} returned {@code false}
     * (no hazard) when {@code rGen <= dependsPercent} — the polarity was inverted.
     * This method correctly returns {@code true} (hazard present) in that case.</p>
     */
    private boolean rSmtDepends(SimulationConfig config) {
        return rng.nextInt(101) <= config.rSmtDependsPercent();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void handlePauseAndDelay() {
        while (control.isPaused()) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long delay = control.getTickDelayMs();
        if (delay > 0) {
            try { Thread.sleep(delay); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static Map<String, Instruction> buildSnapshot(
            Instruction[] fxu, Instruction[] fpu,
            Instruction[] branch, Instruction[] lsu) {
        Map<String, Instruction> map = new LinkedHashMap<>();
        map.put(SLOT_FXU0,   fxu[0]);
        map.put(SLOT_FXU1,   fxu[1]);
        map.put(SLOT_FPU0,   fpu[0]);
        map.put(SLOT_FPU1,   fpu[1]);
        map.put(SLOT_BRANCH, branch[0]);
        map.put(SLOT_LSU,    lsu[0]);
        return Collections.unmodifiableMap(map);
    }

    // ─── Internal result carrier ──────────────────────────────────────────────

    private record PassResult(
            int cycles,
            int intInstructions,
            int smtInstructions,
            int fpuInstructions,
            int lsuInstructions,
            int busyFxu0, int busyFxu1,
            int busyFpu0, int busyFpu1,
            int busyBranch, int busyLsu
    ) {
        Map<String, Double> unitUtilization(int totalCycles) {
            if (totalCycles == 0) return Map.of();
            return Map.of(
                    SLOT_FXU0,   pct(busyFxu0,   totalCycles),
                    SLOT_FXU1,   pct(busyFxu1,   totalCycles),
                    SLOT_FPU0,   pct(busyFpu0,   totalCycles),
                    SLOT_FPU1,   pct(busyFpu1,   totalCycles),
                    SLOT_BRANCH, pct(busyBranch, totalCycles),
                    SLOT_LSU,    pct(busyLsu,    totalCycles)
            );
        }
        private static double pct(int busy, int total) {
            return total == 0 ? 0.0 : (busy * 100.0 / total);
        }
    }
}
