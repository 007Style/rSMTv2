# rSMTv2

A modernized Java 21 processor simulator for **Reverse Simultaneous Multi-Threading (rSMT)** — a hard fork of the original [rSMT](https://github.com/007Style/rSMT) by Daneyand Singley, which is the evidence for [IBM Patent #US8595468](https://patents.google.com/patent/US8595468).

> rSMTv2 is a complete rewrite. It preserves the rSMT simulation concept while modernizing the codebase, fixing known bugs, expanding the execution unit model, adding a full 4-stage pipeline, and delivering a live JavaFX desktop GUI with real-time pipeline visualization.

---

## What is Reverse SMT?

**Reverse Simultaneous Multithreading (rSMT)**, also known as **Inverse Hyper-Threading**, allows multiple physical CPU cores to cooperate on a single heavy thread — the opposite of traditional SMT, which splits one core into multiple logical cores for separate threads.

rSMTv2 simulates this concept: it models an out-of-order-capable pipeline and measures the cycle-count reduction when rSMT is active versus inactive, with live visualization of instruction flow through execution units.

---

## rSMTv2 vs rSMT

| Feature | rSMT (original) | rSMTv2 |
|---|---|---|
| Java version | Java 5 (NetBeans/Ant) | Java 21 LTS (Maven) |
| Build | Apache Ant | Maven + Shade plugin |
| Type safety | Raw `Vector`, unchecked casts | `sealed interface`, `record`, generics |
| Instruction model | Index-based (`int index`) | Typed subtypes (`FxuInstruction`, `FpuInstruction`, …) |
| Execution units | FXU×2, FPU×1, Branch×1 | FXU×2, FPU×2, Branch×1, LSU×1 |
| Pipeline model | Flat clock-tick | 4-stage: Fetch → Decode → Execute → Retire |
| Hazard tracking | Partial (branch stall only) | Structural, data, and control hazards with counters |
| IPC | Not reported | Reported live and in final summary |
| GUI | None | JavaFX desktop: live pipeline grid, IPC chart, utilization chart, stall breakdown |
| rSMT toggle | Compile-time only | Live mid-simulation toggle in GUI |
| Animation speed | N/A | 1s / 2s / 5s / 10s / Ludicrous (max) per clock |
| Tests | None | JUnit 5 unit tests for all components |
| Known bugs | FP arithmetic broken; `rSMT_depends()` inverted | Fixed |

---

## Project Structure

```
rSMTv2/
├── rSMT/                          # Read-only: original 2009 source baseline
│   └── src/rsmt/
│       ├── Main.java
│       ├── rSMT.java
│       └── genInstructions.java
├── src/
│   ├── main/java/com/rsmt/
│   │   ├── Main.java              # Entry point: CLI or JavaFX launcher
│   │   ├── core/                  # Data model + GUI interfaces
│   │   │   ├── SimulationConfig.java
│   │   │   ├── SimulationResult.java
│   │   │   ├── ClockEvent.java
│   │   │   ├── Instruction.java   # sealed interface
│   │   │   ├── SimulationListener.java
│   │   │   ├── SimulationControl.java
│   │   │   ├── NoOpSimulationListener.java
│   │   │   ├── StaticSimulationControl.java
│   │   │   └── SimulationReport.java
│   │   ├── sim/                   # Simulation engine
│   │   │   ├── InstructionGenerator.java
│   │   │   ├── SimulationEngine.java
│   │   │   └── PipelineEngine.java
│   │   └── gui/                   # JavaFX desktop UI
│   │       ├── MainApp.java
│   │       ├── SimulationController.java
│   │       ├── PipelineGridView.java
│   │       ├── AnalyticsPanel.java
│   │       ├── GuiSimulationListener.java
│   │       └── GuiSimulationControl.java
│   └── test/java/com/rsmt/
├── pom.xml
├── rSMTv2-plan.md                 # Full project plan with sub-task status
└── AGENTS.md                      # AI assistant guidance
```

---

## Build & Run

Requires **JDK 21** and **Maven 3.8+**.

```bash
# Compile
mvn compile

# Run all tests
mvn test

# Run a single test
mvn test -Dtest=SimulationEngineTest#testDependencyFix

# Build fat JAR
mvn package

# Run CLI (6 args)
java -jar target/rSMTv2.jar <numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>

# Run with defaults (100 inst, 0 delay, 50% int, 20% load, 50% avail, 20% depends)
java -jar target/rSMTv2.jar

# Launch JavaFX GUI
mvn javafx:run
```

---

## CLI Output

```
(1) Generating Instructions... Number: 100  %FXU: 50  %Load: 20
(2) Simulating rSMT Activated... START
CYCLE: 6  rSMT instruction: add(42, -17)
...
(2) Simulating rSMT Activated... FINISHED
**********************************************************
Total Cycles      : 312
Total Instructions: 100
Integer (FXU)     : 54
Integer rSMT      : 11
FPU Instructions  : 20
LSU Instructions  : 18
IPC               : 1.23
Stalls - Structural: 4  Data: 12  Control: 8
**********************************************************
(3) Simulating rSMT Deactivated... START
...
(4)**********************************************************
rSMT performance gain : 108.5%
(4)**********************************************************
```

---

## Execution Units & Latencies

| Unit | Slots | Latency | Instructions |
|---|---|---|---|
| FXU | ×2 (primary + SMT) | 5 clocks | add, sub, mul, div |
| FPU | ×2 | 6 clocks | fadd, fsub, fmul, fdiv |
| Branch | ×1 | 4 clocks | b (stalls fetch until retired) |
| LSU | ×1 | 3 clocks | load, store |
| — | — | 0 clocks | nop |

---

## Authors & Credits

| Name | Role |
|---|---|
| **Daneyand Singley** (`dsingley`) | Original rSMT author (January 2009); rSMTv2 author |

**Original rSMT:** https://github.com/007Style/rSMT  
**IBM Patent:** https://patents.google.com/patent/US8595468

---

## Future Work (out of scope for v2)

- Out-of-order execution: reorder buffer (ROB), register renaming, Tomasulo algorithm
