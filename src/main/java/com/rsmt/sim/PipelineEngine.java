package com.rsmt.sim;

import com.rsmt.core.*;

import java.util.*;

/**
 * 4-stage pipeline engine: Fetch → Decode → Issue → Execute/Retire.
 *
 * <p>This is the internal implementation behind {@link SimulationEngine#run()}.
 * Callers never reference this class directly.</p>
 *
 * <h3>Pipeline model</h3>
 * <pre>
 *  ┌───────┐   ┌────────┐   ┌───────┐   ┌─────────────────────────────┐
 *  │ FETCH │ → │ DECODE │ → │ ISSUE │ → │ EXECUTE (per-unit slots)    │
 *  └───────┘   └────────┘   └───────┘   │  FXU0, FXU1, FPU0, FPU1,   │
 *                                        │  Branch, LSU                │
 *                                        └─────────────────────────────┘
 * </pre>
 *
 * <h3>Stall types modelled</h3>
 * <ul>
 *   <li><b>Control stall</b>: branch in-flight → Fetch stage is frozen.</li>
 *   <li><b>Structural stall</b>: target execution unit full → Issue stage cannot advance.</li>
 *   <li><b>Data stall</b>: {@code rSmtDepends()} returns true → FXU slot 1 issue blocked.</li>
 * </ul>
 *
 * <p>Each stage holds at most one instruction per cycle (single-issue). The pipeline
 * advances one stage per clock tick unless a stall prevents it.</p>
 */
final class PipelineEngine {

    // Latency (clocks) each instruction spends in the Fetch+Decode stages
    // before reaching Issue. Both stages together = 2 cycles pipeline depth.
    private static final int FETCH_LATENCY  = 1;
    private static final int DECODE_LATENCY = 1;

    private final SimulationListener listener;
    private final SimulationControl  control;
    private final InstructionGenerator generator;
    private final Random rng;

    PipelineEngine(SimulationListener listener,
                   SimulationControl control,
                   InstructionGenerator generator,
                   Random rng) {
        this.listener  = listener;
        this.control   = control;
        this.generator = generator;
        this.rng       = rng;
    }

    // ─── Result carrier ──────────────────────────────────────────────────────

    record PipelinePassResult(
            int cycles,
            int intInstructions,
            int smtInstructions,
            int fpuInstructions,
            int lsuInstructions,
            int busyFxu0, int busyFxu1,
            int busyFpu0, int busyFpu1,
            int busyBranch, int busyLsu,
            int structuralStalls,
            int dataStalls,
            int controlStalls
    ) {
        Map<String, Double> unitUtilization(int totalCycles) {
            if (totalCycles == 0) return Map.of();
            return Map.of(
                    SimulationEngine.SLOT_FXU0,   pct(busyFxu0,   totalCycles),
                    SimulationEngine.SLOT_FXU1,   pct(busyFxu1,   totalCycles),
                    SimulationEngine.SLOT_FPU0,   pct(busyFpu0,   totalCycles),
                    SimulationEngine.SLOT_FPU1,   pct(busyFpu1,   totalCycles),
                    SimulationEngine.SLOT_BRANCH, pct(busyBranch, totalCycles),
                    SimulationEngine.SLOT_LSU,    pct(busyLsu,    totalCycles)
            );
        }
        private static double pct(int busy, int total) {
            return total == 0 ? 0.0 : (busy * 100.0 / total);
        }
    }

    // ─── Main pass loop ───────────────────────────────────────────────────────

    /**
     * Runs a single simulation pass (SMT-ON or SMT-OFF) over {@code instructions}.
     * Returns a {@link PipelinePassResult} with full stall and utilization counts.
     */
    PipelinePassResult run(SimulationConfig config,
                           List<Instruction> instructions,
                           boolean smtEnabledDefault) {

        // ── Stage queues ──────────────────────────────────────────────────
        // Each stage holds at most one instruction (single-issue pipeline).
        // null = stage is empty this cycle.
        ArrayDeque<Instruction> fetchQueue  = new ArrayDeque<>(instructions);
        Instruction fetchStage  = null;   // currently in FETCH
        Instruction decodeStage = null;   // currently in DECODE
        // Issue stage just dispatches directly to execution slots each cycle.

        // ── Execution slots ───────────────────────────────────────────────
        // Arrays sized for dual-slot units; index 0 = primary, 1 = SMT/secondary.
        Instruction[] fxu    = new Instruction[2];
        Instruction[] fpu    = new Instruction[2];
        Instruction[] branch = new Instruction[1];
        Instruction[] lsu    = new Instruction[1];

        // ── Counters ──────────────────────────────────────────────────────
        int clock           = 0;
        int intInst         = 0;
        int smtInst         = 0;
        int fpuInst         = 0;
        int lsuInst         = 0;
        int structuralStalls = 0;
        int dataStalls       = 0;
        int controlStalls    = 0;
        boolean execBranch   = false;   // control hazard flag

        // Per-unit busy-cycle counters
        int busyFxu0 = 0, busyFxu1 = 0, busyFpu0 = 0, busyFpu1 = 0,
                busyBranch = 0, busyLsu = 0;

        // ── Cycle loop ────────────────────────────────────────────────────
        while (!fetchQueue.isEmpty()
                || fetchStage  != null
                || decodeStage != null
                || fxu[0] != null || fxu[1] != null
                || fpu[0] != null || fpu[1] != null
                || branch[0] != null || lsu[0] != null) {

            clock++;
            handlePauseAndDelay();
            boolean smtActive = smtEnabledDefault && control.isSmtEnabled();

            // ─ Stall flags for this cycle (computed during Issue, shown in event) ─
            boolean thisControlStall    = false;
            boolean thisStructuralStall = false;
            boolean thisDataStall       = false;

            // ══════════════════════════════════════════════════════════════
            // STAGE 3: ISSUE — dispatch decoded instruction to execution unit
            // ══════════════════════════════════════════════════════════════
            if (decodeStage != null) {
                Instruction inst = decodeStage;

                if (execBranch) {
                    // Control stall: branch in flight, cannot issue new instructions
                    thisControlStall = true;
                    controlStalls++;
                } else {
                    boolean issued = tryIssue(inst, fxu, fpu, branch, lsu,
                            smtActive, config);

                    if (issued == false) {
                        // Structural stall: target unit occupied
                        thisStructuralStall = true;
                        structuralStalls++;
                    } else {
                        // Successfully issued — update counters
                        decodeStage = null;  // instruction left decode stage

                        if (inst instanceof FxuInstruction) {
                            intInst++;
                            // Check if this was issued to FXU1 (SMT slot)
                            if (fxu[1] == inst) { smtInst++; }
                        } else if (inst instanceof FpuInstruction) {
                            fpuInst++;
                        } else if (inst instanceof BranchInstruction) {
                            execBranch = true;
                        } else if (inst instanceof LoadInstruction
                                || inst instanceof StoreInstruction) {
                            lsuInst++;
                        } else if (inst instanceof NopInstruction) {
                            // NOP already retired in tryIssue
                        }

                        // Attempt to issue a second FXU instruction from Decode
                        // (this is the rSMT dual-issue opportunity)
                        if (smtActive && fxu[1] == null && !fetchQueue.isEmpty()
                                && fetchQueue.peek() instanceof FxuInstruction) {
                            if (rSmtCycle(config)) {
                                if (rSmtDepends(config)) {
                                    thisDataStall = true;
                                    dataStalls++;
                                } else {
                                    // Peek the next decoded candidate
                                    Instruction smtCandidate = fetchQueue.peek();
                                    fxu[1] = fetchQueue.poll();
                                    intInst++;
                                    smtInst++;
                                    System.out.println("CYCLE: " + clock
                                            + "  rSMT instruction: "
                                            + generator.instName(fxu[1]));
                                }
                            }
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════════════
            // STAGE 2: DECODE — move fetch → decode if decode is free
            // ══════════════════════════════════════════════════════════════
            if (decodeStage == null && fetchStage != null) {
                decodeStage = fetchStage;
                fetchStage  = null;
            }

            // ══════════════════════════════════════════════════════════════
            // STAGE 1: FETCH — pull next instruction from stream (unless branch stall)
            // ══════════════════════════════════════════════════════════════
            if (!execBranch && fetchStage == null && !fetchQueue.isEmpty()) {
                fetchStage = fetchQueue.poll();
            }

            // ══════════════════════════════════════════════════════════════
            // STAGE 4: EXECUTE / RETIRE — tick all active execution slots
            // ══════════════════════════════════════════════════════════════
            fxu[0]    = tick(fxu[0]);
            fxu[1]    = tickWithDelay(fxu[1], config.rSmtDelay());
            fpu[0]    = tick(fpu[0]);
            fpu[1]    = tick(fpu[1]);
            lsu[0]    = tick(lsu[0]);
            if (branch[0] != null) {
                branch[0] = tick(branch[0]);
                if (branch[0] == null) execBranch = false; // branch retired, unblock fetch
            }

            // ── Count busy slots ──────────────────────────────────────────
            if (fxu[0]    != null) busyFxu0++;
            if (fxu[1]    != null) busyFxu1++;
            if (fpu[0]    != null) busyFpu0++;
            if (fpu[1]    != null) busyFpu1++;
            if (branch[0] != null) busyBranch++;
            if (lsu[0]    != null) busyLsu++;

            // ── Build pipeline stage snapshot for GUI ─────────────────────
            Map<PipelineStage, List<Instruction>> pipelineSnapshot =
                    buildPipelineSnapshot(fetchStage, decodeStage, fxu, fpu, branch, lsu);

            // ── Build execution slot snapshot ─────────────────────────────
            Map<String, Instruction> slotSnapshot =
                    buildSlotSnapshot(fxu, fpu, branch, lsu);

            // ── Compute live IPC (instructions retired / cycles elapsed) ──
            int retired = intInst + fpuInst + lsuInst
                    + (int) instructions.stream().filter(i -> i instanceof BranchInstruction).count();
            double currentIpc = clock > 0 ? (double) retired / clock : 0.0;

            // ── Fire tick event ───────────────────────────────────────────
            listener.onClockTick(new ClockEvent(
                    clock, slotSnapshot, pipelineSnapshot,
                    currentIpc, smtActive,
                    thisStructuralStall, thisDataStall, thisControlStall
            ));
        }

        return new PipelinePassResult(
                clock, intInst, smtInst, fpuInst, lsuInst,
                busyFxu0, busyFxu1, busyFpu0, busyFpu1, busyBranch, busyLsu,
                structuralStalls, dataStalls, controlStalls
        );
    }

    // ─── Issue dispatch ───────────────────────────────────────────────────────

    /**
     * Tries to dispatch {@code inst} to an available execution slot.
     * Returns {@code true} if issued (or retired for NOP), {@code false} on structural stall.
     */
    private boolean tryIssue(Instruction inst,
                              Instruction[] fxu, Instruction[] fpu,
                              Instruction[] branch, Instruction[] lsu,
                              boolean smtActive, SimulationConfig config) {
        return switch (inst) {
            case FxuInstruction fxuInst -> {
                if (fxu[0] == null) {
                    fxu[0] = fxuInst;
                    yield true;
                }
                // FXU0 busy — try SMT slot if enabled
                if (smtActive && fxu[1] == null && rSmtCycle(config) && !rSmtDepends(config)) {
                    fxu[1] = fxuInst;
                    yield true;
                }
                yield false; // structural stall
            }
            case FpuInstruction fpuInst -> {
                if (fpu[0] == null) { fpu[0] = fpuInst; yield true; }
                if (smtActive && fpu[1] == null) { fpu[1] = fpuInst; yield true; }
                yield false;
            }
            case BranchInstruction branchInst -> {
                if (branch[0] == null) { branch[0] = branchInst; yield true; }
                yield false;
            }
            case LoadInstruction loadInst -> {
                if (lsu[0] == null) { lsu[0] = loadInst; yield true; }
                yield false;
            }
            case StoreInstruction storeInst -> {
                if (lsu[0] == null) { lsu[0] = storeInst; yield true; }
                yield false;
            }
            case NopInstruction ignored -> true; // NOP: zero-cycle retire
        };
    }

    // ─── Tick helpers ─────────────────────────────────────────────────────────

    private Instruction tick(Instruction inst) {
        if (inst == null) return null;
        if (getCurClock(inst) >= getDoneClock(inst)) {
            generator.execute(inst);
            return null; // retired
        }
        return advanceClock(inst);
    }

    private Instruction tickWithDelay(Instruction inst, int delay) {
        if (inst == null) return null;
        if (getCurClock(inst) >= getDoneClock(inst) + delay) {
            generator.execute(inst);
            return null;
        }
        return advanceClock(inst);
    }

    // ─── rSMT probability checks ──────────────────────────────────────────────

    private boolean rSmtCycle(SimulationConfig config) {
        return rng.nextInt(101) <= config.rSmtAvailPercent();
    }

    /** Returns true = hazard present (blocks FXU1 issue). Fixed polarity vs original. */
    private boolean rSmtDepends(SimulationConfig config) {
        return rng.nextInt(101) <= config.rSmtDependsPercent();
    }

    // ─── Control ──────────────────────────────────────────────────────────────

    private void handlePauseAndDelay() {
        while (control.isPaused()) {
            try { Thread.sleep(50); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        long delay = control.getTickDelayMs();
        if (delay > 0) {
            try { Thread.sleep(delay); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ─── Snapshot builders ────────────────────────────────────────────────────

    private static Map<String, Instruction> buildSlotSnapshot(
            Instruction[] fxu, Instruction[] fpu,
            Instruction[] branch, Instruction[] lsu) {
        Map<String, Instruction> map = new LinkedHashMap<>();
        map.put(SimulationEngine.SLOT_FXU0,   fxu[0]);
        map.put(SimulationEngine.SLOT_FXU1,   fxu[1]);
        map.put(SimulationEngine.SLOT_FPU0,   fpu[0]);
        map.put(SimulationEngine.SLOT_FPU1,   fpu[1]);
        map.put(SimulationEngine.SLOT_BRANCH, branch[0]);
        map.put(SimulationEngine.SLOT_LSU,    lsu[0]);
        return Collections.unmodifiableMap(map);
    }

    private static Map<PipelineStage, List<Instruction>> buildPipelineSnapshot(
            Instruction fetchStage, Instruction decodeStage,
            Instruction[] fxu, Instruction[] fpu,
            Instruction[] branch, Instruction[] lsu) {

        List<Instruction> executeSlots = new ArrayList<>();
        for (Instruction i : new Instruction[]{fxu[0], fxu[1], fpu[0], fpu[1], branch[0], lsu[0]}) {
            if (i != null) executeSlots.add(i);
        }

        Map<PipelineStage, List<Instruction>> map = new EnumMap<>(PipelineStage.class);
        map.put(PipelineStage.FETCH,   fetchStage  != null ? List.of(fetchStage)  : List.of());
        map.put(PipelineStage.DECODE,  decodeStage != null ? List.of(decodeStage) : List.of());
        map.put(PipelineStage.ISSUE,   List.of()); // issue is a decision point, not a storage stage
        map.put(PipelineStage.EXECUTE, Collections.unmodifiableList(executeSlots));
        return Collections.unmodifiableMap(map);
    }

    // ─── Clock field helpers (records are immutable) ──────────────────────────

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
}
