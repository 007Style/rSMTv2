package com.rsmt.sim;

import com.rsmt.core.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a random instruction stream and executes individual instructions.
 *
 * <p>Use a fixed seed for reproducible simulations (required for SMT-ON vs OFF comparison).</p>
 *
 * <h3>Fixes vs original {@code genInstructions.java}</h3>
 * <ul>
 *   <li>FP arithmetic block no longer checks {@code index 0–3} — uses sealed type switch</li>
 *   <li>Generics throughout — no raw types</li>
 *   <li>LSU instructions (load/store) added</li>
 * </ul>
 */
public final class InstructionGenerator {

    // Latency constants (must match rSMT.java originals)
    public static final int FX_CYCLES  = 5;
    public static final int FP_CYCLES  = 6;
    public static final int BR_CYCLES  = 4;
    public static final int LSU_CYCLES = 3;
    public static final int NOP_CYCLES = 0;

    // FXU op indices
    public static final int ADD = 0, SUB = 1, MUL = 2, DIV = 3;
    // FPU op indices
    public static final int FADD = 4, FSUB = 5, FMUL = 6, FDIV = 7;

    private final Random rng;

    /**
     * Creates a generator with the given seed.
     * Use {@code System.currentTimeMillis()} for a random run,
     * or a fixed value (e.g. {@code 42L}) for reproducible tests.
     */
    public InstructionGenerator(long seed) {
        this.rng = new Random(seed);
    }

    /**
     * Generates {@code config.numInstructions()} instructions respecting the
     * {@code percentInt} and {@code percentLoad} mix from the config.
     *
     * <p>Mix logic:
     * <ul>
     *   <li>A roll ≤ {@code percentInt} → FXU instruction</li>
     *   <li>Otherwise → roll again: ≤ {@code percentLoad} → LSU; else → FPU or Branch (equal chance)</li>
     * </ul>
     * </p>
     */
    public List<Instruction> generate(SimulationConfig config) {
        List<Instruction> result = new ArrayList<>(config.numInstructions());
        System.out.println("(1) Generating Instructions... Number: " + config.numInstructions()
                + "  %FXU: "    + config.percentFxu()
                + "  %FPU: "    + config.percentFpu()
                + "  %Branch: " + config.percentBranch()
                + "  %LSU: "    + config.percentLsu()
                + "  %NOP: "    + config.percentNop());

        // Cumulative thresholds for a single roll in [0, 100]
        // FXU: [0, fxu)  FPU: [fxu, fxu+fpu)  Branch: [...]  LSU: [...]  NOP: remainder
        int thFxu    = config.percentFxu();
        int thFpu    = thFxu    + config.percentFpu();
        int thBranch = thFpu    + config.percentBranch();
        int thLsu    = thBranch + config.percentLsu();
        // [thLsu, 100] → NOP

        for (int i = 0; i < config.numInstructions(); i++) {
            int roll = rng.nextInt(100);  // 0–99 inclusive (100 equal slots)
            if (roll < thFxu) {
                result.add(makeFxu(i, config.fxCycles()));
            } else if (roll < thFpu) {
                result.add(makeFpu(i, config.fpCycles()));
            } else if (roll < thBranch) {
                result.add(new BranchInstruction(i, 0, config.brCycles()));
            } else if (roll < thLsu) {
                result.add(makeLsu(i, config.lsuCycles()));
            } else {
                result.add(new NopInstruction(i, 0, NOP_CYCLES));
            }
        }

        System.out.println("Generated: " + result.size() + " instructions.");
        return result;
    }

    // ─── Factory helpers ─────────────────────────────────────────────────────

    private FxuInstruction makeFxu(int order, int latency) {
        int opIndex = rng.nextInt(4); // 0–3
        int op1 = rng.nextInt();
        int op2 = rng.nextInt();
        // Guard against division by zero for div
        if (opIndex == DIV && op2 == 0) op2 = 1;
        return new FxuInstruction(order, 0, latency, op1, op2, opIndex, 0);
    }

    private FpuInstruction makeFpu(int order, int latency) {
        int opIndex = rng.nextInt(4) + 4; // 4–7
        double dop1 = rng.nextDouble();
        double dop2 = rng.nextDouble();
        // Guard against FP division by zero
        if (opIndex == FDIV && dop2 == 0.0) dop2 = 1.0;
        return new FpuInstruction(order, 0, latency, dop1, dop2, opIndex, 0.0);
    }

    private Instruction makeLsu(int order, int latency) {
        boolean isStore = rng.nextBoolean();
        int address = rng.nextInt(65536); // 64K address space
        if (isStore) {
            return new StoreInstruction(order, 0, latency, address, rng.nextInt());
        } else {
            return new LoadInstruction(order, 0, latency, address);
        }
    }

    // ─── Execution ───────────────────────────────────────────────────────────

    /**
     * Executes an instruction: computes its result and returns a new record with
     * {@code curClock} advanced to {@code doneClock} (retired state).
     *
     * <p><b>Bug fix</b>: the original {@code genInstructions.java:execute()} checked
     * {@code if(exe.index == 0..3)} for FPU arithmetic, which always re-evaluated
     * FXU indices — FP operations were never computed. This method uses sealed-type
     * pattern matching so each arm is unambiguous.</p>
     */
    public Instruction execute(Instruction inst) {
        return switch (inst) {
            case FxuInstruction fxu -> {
                int r = switch (fxu.opIndex()) {
                    case ADD -> fxu.op1() + fxu.op2();
                    case SUB -> fxu.op1() - fxu.op2();
                    case MUL -> fxu.op1() * fxu.op2();
                    case DIV -> fxu.op2() != 0 ? fxu.op1() / fxu.op2() : 0;
                    default  -> 0;
                };
                yield new FxuInstruction(fxu.order(), fxu.doneClock(), fxu.doneClock(),
                        fxu.op1(), fxu.op2(), fxu.opIndex(), r);
            }
            case FpuInstruction fpu -> {
                // FIX: was checking index 0–3 (FXU indices) — now correctly uses FPU opIndex 4–7
                double dr = switch (fpu.opIndex()) {
                    case FADD -> fpu.dop1() + fpu.dop2();
                    case FSUB -> fpu.dop1() - fpu.dop2();
                    case FMUL -> fpu.dop1() * fpu.dop2();
                    case FDIV -> fpu.dop2() != 0.0 ? fpu.dop1() / fpu.dop2() : 0.0;
                    default   -> 0.0;
                };
                yield new FpuInstruction(fpu.order(), fpu.doneClock(), fpu.doneClock(),
                        fpu.dop1(), fpu.dop2(), fpu.opIndex(), dr);
            }
            case LoadInstruction  l  -> new LoadInstruction(l.order(), l.doneClock(), l.doneClock(), l.address());
            case StoreInstruction s  -> new StoreInstruction(s.order(), s.doneClock(), s.doneClock(), s.address(), s.value());
            case BranchInstruction b -> new BranchInstruction(b.order(), b.doneClock(), b.doneClock());
            case NopInstruction n    -> new NopInstruction(n.order(), n.doneClock(), n.doneClock());
        };
    }

    /**
     * Returns a human-readable display string for an instruction (for console logging).
     */
    public String instName(Instruction inst) {
        return switch (inst) {
            case FxuInstruction fxu -> {
                String op = switch (fxu.opIndex()) {
                    case ADD -> "add"; case SUB -> "sub";
                    case MUL -> "mul"; case DIV -> "div";
                    default  -> "fxu?";
                };
                yield op + "(" + fxu.op1() + ", " + fxu.op2() + ")";
            }
            case FpuInstruction fpu -> {
                String op = switch (fpu.opIndex()) {
                    case FADD -> "fadd"; case FSUB -> "fsub";
                    case FMUL -> "fmul"; case FDIV -> "fdiv";
                    default   -> "fpu?";
                };
                yield op + "(" + fpu.dop1() + ", " + fpu.dop2() + ")";
            }
            case LoadInstruction  l  -> "load(0x" + Integer.toHexString(l.address()) + ")";
            case StoreInstruction s  -> "store(0x" + Integer.toHexString(s.address()) + ", " + s.value() + ")";
            case BranchInstruction b -> "b";
            case NopInstruction    n -> "nop";
        };
    }
}
