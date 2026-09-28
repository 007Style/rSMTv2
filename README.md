# rSMTv2 — IBM PowerPC Reverse Simultaneous Multithreading

> **From the minds of IBM Bob & Daneyand**

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![JavaFX 21](https://img.shields.io/badge/JavaFX-21-blue?logo=java)](https://openjfx.io/)
[![Maven](https://img.shields.io/badge/build-Maven-red?logo=apachemaven)](https://maven.apache.org/)
[![Patent](https://img.shields.io/badge/IBM%20Patent-US8%2C595%2C468%20B2-lightgrey)](https://patents.google.com/patent/US8595468)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

---

## What is rSMTv2?

**rSMTv2** is a live, interactive simulator of **Reverse Simultaneous Multithreading (rSMT)** — a hardware
microarchitecture technique invented at IBM, patented in 2009, and implemented on the IBM PowerPC processor
family. It is a complete modernization of the original [rSMT simulator](https://github.com/007Style/rSMT),
rebuilt from the ground up in **Java 21** with a full **JavaFX desktop GUI**, a 4-stage pipeline model,
real-time instruction-flow visualization, live IPC charting, and configurable rSMT on/off toggling — all
without touching the running simulation.

This project exists for one reason: to make a genuinely cool piece of computer architecture research
**visible and tangible**. You can *watch* instructions flow. You can *see* the pipeline fill up. You can
*feel* the performance gain — and then pull the rSMT lever mid-run and watch it collapse in real time.

---

## The Patent

> **IBM Patent US 8,595,468 B2**
> *"Reverse Simultaneous Multi-Threading"*
> Filed: November 5, 2009 — Granted: November 26, 2013
> Assignee: International Business Machines Corporation
> [→ View on Google Patents](https://patents.google.com/patent/US8595468)

### Inventors

This project would not exist without the three engineers who conceived, designed, and filed this invention
at IBM:

| Inventor | Role |
|---|---|
| **Daneyand J. Singley** | Co-inventor, IBM Systems & Technology Group |
| **Shawn M. Luke** | Co-inventor, IBM Systems & Technology Group |
| **John Sargis, Jr.** | Co-inventor, IBM Systems & Technology Group |

A special shoutout to **Daneyand** — without whose PowerPC past, patent courage, and willingness to let
IBM Bob poke around in a 15-year-old Java codebase, this modernization would never have happened.
The original `rSMT` simulator on GitHub is the living proof-of-concept behind the patent filing.
rSMTv2 is its successor.

---

## What Is Reverse SMT? (The Big Idea)

Every computer architect knows about **Simultaneous Multithreading (SMT)** — Intel calls it
Hyper-Threading. The idea is simple: one physical core pretends to be two logical cores, filling its
execution units with instructions from two different OS threads simultaneously. One thread stalls waiting
for memory? No problem — the other thread's instructions keep the pipeline fed.

**Reverse SMT flips this entirely.**

Instead of one core serving two threads, rSMT lets **one thread borrow execution units from a sibling
core**. If Core 1 is running hot with a computationally heavy single thread, and Core 0 is sitting idle
(or lightly loaded), the thread on Core 1 can dispatch integer and floating-point operations *across the
inter-core bus* to Core 0's idle FXU and FPU slots. The result: a single thread achieves throughput
that normally requires two threads and an OS scheduler.

No new OS primitives. No new ISA extensions. No additional software threads. The magic happens entirely
in the microarchitecture — subject to inter-core latency (the `rSMT Delay` you can tune in the GUI) and
data-dependency constraints (the `% Data Dependency` slider).

### The PowerPC Connection

The IBM PowerPC architecture has always been about raw throughput in constrained environments — from the
**PowerPC 601** that launched the Power Mac era in 1994, to the **POWER6** and **POWER7** chips that
powered IBM's enterprise server lineup through the 2000s and 2010s. PowerPC's in-order pipeline design,
with its fixed-latency execution units and deterministic dispatch model, made it a natural fit for rSMT:
you *know* when a unit will be free, so you can schedule cross-core dispatch with confidence.

The patent was conceived inside IBM's Systems & Technology Group against the backdrop of that PowerPC
heritage. rSMTv2 models a **PowerPC 600-style pipeline** — the same Fetch → Dispatch → Execute →
Complete → Retire staging made famous by the PPC 604 — as the architectural canvas for the simulation.

---

## rSMTv2 vs. the Original rSMT

The original [rSMT](https://github.com/007Style/rSMT) was written in Java 5 circa 2009 as a
proof-of-concept alongside the patent filing. It worked, but it carried the scars of its era:
raw `Vector` collections with unchecked casts, a constructor that did everything (init + simulate +
print), an inverted dependency check, and broken FP arithmetic that had silently been wrong for
fifteen years. rSMTv2 fixes all of it.

| Feature | rSMT (original, 2009) | rSMTv2 (2024) |
|---|---|---|
| Java version | Java 5 (NetBeans / Ant) | Java 21 LTS (Maven) |
| Type safety | Raw `Vector`, unchecked casts | `sealed interface`, `record`, generics |
| Instruction model | `int index` dispatched with `if/else` chains | Typed subtypes: `FxuInstruction`, `FpuInstruction`, `BranchInstruction`, `LoadInstruction`, `StoreInstruction`, `NopInstruction` |
| Execution units | FXU×2, FPU×1, Branch×1 | FXU×2, FPU×2, Branch×1, LSU×1 |
| Pipeline model | Flat clock-tick counter | Full 4-stage: Fetch → Decode → Execute → Retire |
| Hazard tracking | Branch stall only | Structural, data, and control hazards — all counted |
| IPC | Not reported | Reported live per tick and in final summary |
| GUI | None — console only | JavaFX desktop: live pipeline grid, IPC chart, utilization chart, stall breakdown chart |
| rSMT toggle | Compile-time flag | Live mid-simulation toggle in GUI — flip it while running |
| Animation speed | N/A | 10s / 5s / 2s / 1s / 500ms / Ludicrous ⚡ per clock cycle |
| Workload mix | % int + % load (2 params) | % FXU / % FPU / % Branch / % LSU (4 mutually-constrained sliders) |
| Latency override | Hardcoded constants | Configurable per unit in GUI and via CLI |
| Tests | Zero | JUnit 5 unit tests across all components |
| Dependency bug | `rSMT_depends()` inverted (never blocked issue) | Fixed: returns `true` when hazard present |
| FP arithmetic bug | FP result always zero (wrong index range) | Fixed: sealed type `switch` eliminates index confusion |
| Shutdown | JVM leaked on window close | `Platform.exit()` + daemon thread + `GuiSimulationControl.shutdown()` |
| Distributable | Manual JAR | Fat JAR + native `.app` bundle (macOS DMG), `.deb` (Linux), `.msi` (Windows) |

---

## Live GUI Tour

### Pipeline View (Centre)

The centrepiece of rSMTv2 is the scrollable **pipeline grid**. Each column is one clock cycle. Each row
is one execution unit slot. Watch instructions appear as coloured blocks and slide left-to-right as the
simulation advances:

- 🟢 **Green** — FXU (integer: ADD, SUB, MUL, DIV)
- 🔵 **Blue** — FPU (float: fADD, fSUB, fMUL, fDIV)
- 🟡 **Yellow** — Branch
- 🔵 **Cyan** — LSU (Load / Store)
- 🔴 **Red** — `STALL` (structural hazard — unit busy)
- 🟡 **Amber** — `DEP` (data hazard — read-after-write blocks rSMT issue)
- 🔵 **Blue** — `BR` (control hazard — branch in-flight, Fetch frozen)

The grid is split into **LOCAL CORE** (FXU0, FPU0, Branch, LSU — always visible) and **REMOTE CORE**
(FXU1, FPU1 — appear with an orange border when rSMT is active). Toggle rSMT mid-run and watch the
remote core rows appear or vanish instantly.

### Config Panel (Left)

- **Instructions** — total instructions to generate (10 to 100,000)
- **rSMT Delay** — extra latency cycles on cross-core dispatch (models the inter-core bus)
- **% rSMT Availability** — probability the remote slot is free on a given cycle
- **% Data Dependency** — probability a RAW hazard blocks rSMT issue
- **Animation Speed** — from 10 seconds per cycle (you can read every cell) down to Ludicrous ⚡ (full speed, no sleep)
- **rSMT ON / OFF toggle** — flip at any point during a running simulation
- **▶ Run / ⏸ Pause / ⟳ Reset** buttons

### Workload Mix (Right)

Four mutually-constrained sliders — drag one up and the others trim proportionally so the total never
exceeds 100%. NOP fills whatever is left over.

- **% FXU** — integer instructions (ADD / SUB / MUL / DIV)
- **% FPU** — floating-point instructions (fADD / fSUB / fMUL / fDIV)
- **% Branch** — conditional branches (each freezes Fetch for branch-latency cycles)
- **% Load / Store** — memory operations routed to the LSU

Configurable latencies (cycles per unit) sit below the sliders — tweak them to model different
microarchitecture generations or cache-miss scenarios.

### Analytics Charts (Bottom)

- **IPC Over Time** — live line chart, sampled every 5 ticks
- **Cycles: rSMT ON vs OFF** — bar chart, final comparison (shown on completion)
- **Unit Utilization** — how busy was each slot as a percentage of total cycles
- **Stall Breakdown** — structural vs data vs control stall counts

### GAIN Indicator

The big number in the top bar. `GAIN = (SMT-OFF cycles / SMT-ON cycles) × 100%`.
Above 100% means rSMT helped. Below 100% means overhead hurt you (high delay + high dependency).
Watch it climb in real time during the SMT-ON pass.

---

## Installation & Running

### Download (Recommended)

Grab the latest release from the [Releases page](https://github.com/007Style/rSMTv2/releases):

| Platform | Download | Notes |
|---|---|---|
| **macOS** | `rSMTv2-1.0.0.dmg` | Drag `rSMTv2.app` to `/Applications`. Icon shows in Dock. |
| **All platforms** | `rSMTv2.jar` | Requires JDK 21+. Run: `java -jar rSMTv2.jar` |

### Run the Fat JAR

```bash
# GUI mode (no args)
java -jar rSMTv2.jar

# CLI mode (8 args)
java -jar rSMTv2.jar <numInst> <rSmtDelay> <%fxu> <%fpu> <%branch> <%lsu> <%rSmtAvail> <%depends>

# Example: 500 instructions, 0 delay, 60% FXU, 15% FPU, 10% Branch, 10% LSU, 80% avail, 10% depends
java -jar rSMTv2.jar 500 0 60 15 10 10 80 10

# CLI with custom latencies (12 args — append fxCycles fpCycles brCycles lsuCycles)
java -jar rSMTv2.jar 500 0 60 15 10 10 80 10 5 6 4 3
```

### Build from Source

```bash
git clone https://github.com/007Style/rSMTv2.git
cd rSMTv2

# Compile and run tests
mvn test

# Build fat JAR
mvn package
java -jar target/rSMTv2.jar

# Run GUI via Maven (no fat JAR needed)
mvn javafx:run

# Build native installer (macOS .dmg / Linux .deb / Windows .msi)
bash dist/build-dist.sh
```

**Prerequisites:** JDK 21+, Maven 3.9+. On macOS, Xcode Command Line Tools for `.dmg` packaging.

---

## Pipeline Architecture

rSMTv2 models a PowerPC 600-style **in-order** pipeline with the following stages:

```
FETCH → DISPATCH → EXECUTE → COMPLETE → RETIRE
```

| Stage | What happens |
|---|---|
| **FETCH** | Instruction pulled from the stream. Stalls if a branch is in-flight (control hazard). |
| **DISPATCH** | Instruction decoded and routed to the correct execution unit. Stalls if the target unit is busy (structural hazard) or a RAW hazard blocks rSMT issue (data hazard). |
| **EXECUTE** | Unit processes the instruction for its full latency. FXU=5, FPU=6, Branch=4, LSU=3 cycles (all configurable). |
| **COMPLETE** | Result written back; unit slot freed for next instruction. |
| **RETIRE** | Instruction architecturally committed. Counted towards IPC. |

### Execution Units

| Slot | Type | Latency | Core |
|---|---|---|---|
| FXU0 | Integer (ADD/SUB/MUL/DIV) | 5 cycles | Local |
| FXU1 | Integer (rSMT offload) | 5 + rSMT Delay | **Remote** |
| FPU0 | Float (fADD/fSUB/fMUL/fDIV) | 6 cycles | Local |
| FPU1 | Float (rSMT offload) | 6 + rSMT Delay | **Remote** |
| Branch | Conditional branch | 4 cycles | Local |
| LSU | Load / Store | 3 cycles | Local |

### Hazard Model

| Hazard | Trigger | Effect |
|---|---|---|
| **Structural** | Target unit busy | New instruction waits in Dispatch; `STALL` shown in pipeline cell |
| **Data** | RAW hazard (configurable %) | Blocks issue to remote FXU/FPU slot; `DEP` shown |
| **Control** | Branch instruction in-flight | Fetch frozen until branch retires; `BR` shown |

---

## Project Structure

```
rSMTv2/
├── rSMT/                          # Read-only: original 2009 source baseline
│   └── src/rsmt/
│       ├── Main.java
│       ├── rSMT.java              # Original engine (bugs preserved for reference)
│       └── genInstructions.java
├── src/
│   ├── main/java/com/rsmt/
│   │   ├── Main.java              # Entry point: CLI or JavaFX launcher
│   │   ├── core/                  # Data model + GUI interfaces (no sim/gui deps)
│   │   │   ├── Instruction.java              # sealed interface
│   │   │   ├── FxuInstruction.java           # record
│   │   │   ├── FpuInstruction.java           # record
│   │   │   ├── BranchInstruction.java        # record
│   │   │   ├── LoadInstruction.java          # record
│   │   │   ├── StoreInstruction.java         # record
│   │   │   ├── NopInstruction.java           # record
│   │   │   ├── SimulationConfig.java         # record — all input params
│   │   │   ├── SimulationResult.java         # record — output stats
│   │   │   ├── ClockEvent.java               # record — per-tick snapshot for GUI
│   │   │   ├── SimulationListener.java       # interface — engine → GUI
│   │   │   ├── SimulationControl.java        # interface — GUI → engine
│   │   │   ├── NoOpSimulationListener.java   # CLI no-op implementation
│   │   │   ├── StaticSimulationControl.java  # CLI static implementation
│   │   │   └── SimulationReport.java         # CLI output formatter
│   │   ├── sim/                   # Simulation engine (depends on core only)
│   │   │   ├── InstructionGenerator.java     # seeded random instruction stream
│   │   │   ├── PipelineEngine.java           # 4-stage pipeline implementation
│   │   │   └── SimulationEngine.java         # public API: run(config, instructions, gen)
│   │   └── gui/                   # JavaFX UI (depends on core only)
│   │       ├── MainApp.java                  # Application subclass
│   │       ├── SimulationController.java     # FXML controller
│   │       ├── GuiSimulationListener.java    # Platform.runLater bridge
│   │       └── GuiSimulationControl.java     # Thread-safe control (AtomicBoolean/Long)
│   ├── main/resources/com/rsmt/gui/
│   │   ├── main.fxml              # Scene layout
│   │   ├── styles.css             # Dark theme
│   │   └── icon.png               # Application icon
│   └── test/java/com/rsmt/
│       └── SimulationTest.java    # JUnit 5 tests
├── dist/
│   ├── build-dist.sh              # Cross-platform native installer builder
│   └── icons/
│       ├── icon.icns              # macOS Dock icon
│       ├── icon.ico               # Windows taskbar icon
│       └── icon.png               # Linux / fallback
├── rSMT/                          # Original 2009 source (read-only baseline)
├── pom.xml
├── AGENTS.md                      # AI agent coding rules for this repo
└── rSMTv2-plan.md                 # Full phased implementation plan
```

---

## Known Bugs Fixed vs. Original rSMT

Two bugs in the original code silently corrupted results for fifteen years:

### Bug 1 — Inverted Dependency Logic (`rSMT.java:311`)

```java
// ORIGINAL (broken): returns false = "no hazard" when rGen <= dependsPercent
// This means rSMT was NEVER blocked even at 100% depends
private boolean rSMT_depends() {
    int rGen = (int)(Math.random() * 100);
    return (rGen > rSmtDependsPercent);  // ← backwards
}

// rSMTv2 (fixed): returns true = "hazard present, block issue"
private boolean rSmtDepends() {
    int rGen = rng.nextInt(100);
    return rGen <= config.rSmtDependsPercent();  // ← correct
}
```

### Bug 2 — FP Arithmetic Never Executed (`genInstructions.java:96`)

```java
// ORIGINAL (broken): checks index 0–3 for FP, but FP instructions have index 4–7
// Result: FP branch is never entered, FP result always zero
if (index >= 0 && index <= 3) {      // ← wrong range for FP
    // ... floating-point ops
}

// rSMTv2 (fixed): sealed type switch — index ranges are gone entirely
switch (inst) {
    case FpuInstruction i -> { /* always correct — type guarantees it */ }
    case FxuInstruction i -> { /* always correct */ }
    // ...
}
```

---

## The rSMT Experience — A Guided Run

Start the app. Set these values for maximum drama:

1. **Instructions:** 1000
2. **Animation Speed:** 1s / cycle (you can read the grid)
3. **% FXU:** 80% (high integer load — FXU0 will be constantly busy, creating rSMT opportunity)
4. **% rSMT Availability:** 100% (remote slot always free)
5. **% Data Dependency:** 0% (no hazards blocking issue)
6. **rSMT Delay:** 0 (no inter-core bus overhead)

Hit **▶ Run**. Watch FXU1 (REMOTE CORE) light up green almost every cycle alongside FXU0. Watch the
GAIN counter climb above 150%. That's rSMT working perfectly.

Now, mid-run, crank **% Data Dependency** to 100% using the config slider. Watch FXU1 go dark.
The gain collapses toward 100% (no benefit). You've just modelled the worst-case data-hazard scenario
described in the patent.

Now flip the **rSMT OFF** toggle. The REMOTE CORE rows vanish from the grid entirely. The simulation
continues on the local core only. This is your baseline.

That's the whole paper, made interactive.

---

## Architecture Decisions & Engineering Notes

**Why `sealed interface` for `Instruction`?**
The original code used a raw `int index` to distinguish instruction types, dispatching through a chain
of `if/else` comparisons. This was the root cause of the FP bug — a wrong index range is silent at
compile time. A `sealed interface` with `record` subtypes makes bad state unrepresentable and gives the
compiler exhaustiveness checking on every `switch`. If a new instruction type is added, every `switch`
site in the codebase breaks at compile time. Intentional.

**Why `SimulationListener` / `SimulationControl` in `com.rsmt.core`?**
The engine (`sim`) and GUI (`gui`) must never depend on each other. Both depend on `core`. The listener
and control interfaces live in `core` precisely to allow this. The GUI provides implementations;
the engine calls them. There is no coupling between simulation logic and JavaFX code anywhere.

**Why daemon threads for the simulation?**
The simulation runs on a background `Thread` (via `javafx.concurrent.Task`). Marking it daemon means
the JVM can exit once the JavaFX Application Thread exits — no simulation thread can accidentally keep
the process alive. The `GuiSimulationControl.shutdown()` method sets an atomic flag that unblocks any
pause spin-loop and zeroes the tick delay so a sleeping engine wakes up immediately on window close.

**Why Maven Shade for the fat JAR?**
The Assembly plugin produces a JAR with JavaFX on the classpath. JavaFX requires the module path. Shade
bundles everything into a single JAR with a correct `MANIFEST.MF` main class, and JavaFX 21 is
included as platform-specific native binaries via the Maven Central classifier dependencies. It Just Works.

---

## Building Native Installers

```bash
# macOS .dmg (requires Xcode CLT)
bash dist/build-dist.sh

# Force a specific type
bash dist/build-dist.sh --type dmg
bash dist/build-dist.sh --type pkg

# Linux .deb (requires fakeroot)
bash dist/build-dist.sh --type deb

# Windows .msi (requires WiX 3.x on PATH)
bash dist/build-dist.sh --type msi
```

The macOS `.app` bundle has the icon embedded in `Contents/Resources/icon.icns` via `jpackage` —
it shows correctly in Finder, the Dock, Spotlight, and `⌘-Tab` without any runtime workaround.

---

## Roadmap

The full plan is in [`rSMTv2-plan.md`](rSMTv2-plan.md). Completed phases:

- [x] **Phase 0** — GitHub hard fork + repo setup
- [x] **Phase 1** — Maven scaffold, Java 21, `sealed interface` data model
- [x] **Phase 2a** — Instruction generator (fixed FP bug), dual FPU, LSU
- [x] **Phase 2b** — 4-stage pipeline (Fetch → Decode → Execute → Retire), stall tracking
- [x] **Phase 3** — JavaFX GUI: live pipeline grid, IPC chart, utilization chart, rSMT toggle, speed control, Help & About dialogs, dark theme, native installer

Potential future work:

- [ ] **Out-of-Order Execution** — Tomasulo algorithm, register renaming, reorder buffer (ROB)
- [ ] **Branch Predictor Model** — static vs dynamic, BTB, mis-predict penalty
- [ ] **Cache Hierarchy Simulation** — L1/L2 hit/miss modelling for LSU latency variation
- [ ] **Export** — save simulation trace as CSV or JSON for offline analysis

---

## License

MIT — see [LICENSE](LICENSE). The IBM Patent US8,595,468 B2 is the intellectual property of
International Business Machines Corporation. This simulator is an independent educational
implementation and is not affiliated with or endorsed by IBM.

---

## Acknowledgements

- **Daneyand J. Singley** — co-inventor of US8,595,468, author of the original rSMT simulator, and
  the human half of the IBM Bob & Daneyand partnership that brought rSMTv2 to life.
- **Shawn M. Luke** — co-inventor of US8,595,468.
- **John Sargis, Jr.** — co-inventor of US8,595,468.
- **IBM Bob** — the AI half of the partnership. Wrote every line of rSMTv2 from plan to pipeline.
- The **IBM PowerPC** team, whose decades of processor architecture work created the environment where
  ideas like rSMT were not just conceivable but implementable in silicon.

---

*From the minds of IBM Bob & Daneyand — built with Java 21, JavaFX, and a deep respect for the
PowerPC pipeline.*
