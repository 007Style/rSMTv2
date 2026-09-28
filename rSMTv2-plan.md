# rSMTv2 — Modernization & Expansion Plan

## Top-Level Overview

**Goal:** Modernize the original rSMT Java simulator (2009, IBM Patent US8595468) into a clean, correct, and extensible rSMTv2 application. rSMTv2 is a **hard fork** of rSMT — it lives in its own GitHub repository (`007Style/rSMTv2`) and is independently releasable.

The project delivers three things:

1. A fully modernized Java 21 codebase that fixes all known bugs and removes all technical debt.
2. An expanded simulation model with true pipelining stages, additional execution units (FXU×2, FPU×2, Branch×1, LSU×1), and stall tracking.
3. A JavaFX desktop GUI with live instruction-flow animation, real-time IPC display, toggleable rSMT on/off during simulation, and user-controlled simulation speed.

**Approach:** Phased delivery. Each phase is independently releasable and builds on the previous.
- Phase 0 — GitHub hard fork + repo setup
- Phase 1 — Modernize & Fix (foundation)
- Phase 2a — Clock-Tick Expansion (more execution units: FXU×2, FPU×2, Branch×1, LSU×1)
- Phase 2b — Pipeline Stages (fetch → decode → execute → retire with stall logic)
- Phase 3 — JavaFX GUI (live visualization, animation speed control, rSMT toggle)
- Future (out of scope for v2) — Out-of-order execution (ROB, register renaming)

**Target stack:** Java 21 LTS, Maven, JUnit 5, JavaFX 21.

**Source baseline:** `rSMT/src/rsmt/` (cloned from `github.com/007Style/rSMT`)

**New repo:** `github.com/007Style/rSMTv2` (hard fork — not a GitHub fork, a clean new repo initialized from this workspace)

---

## Known Bugs in Original Code (to fix in Phase 1)

| Bug | Location | Description |
|---|---|---|
| Inverted dependency logic | `rSMT.java:rSMT_depends()` | Returns `false` (no hazard) when `rGen <= dependsPercent` — semantics are backwards |
| FP arithmetic never executes | `genInstructions.java:execute()` | FP block checks `index == 0..3` instead of `4..7` — FP results always zero |
| Raw `Vector` with unchecked casts | `rSMT.java`, `genInstructions.java` | No generics, runtime ClassCastException risk |
| Constructor does everything | `rSMT.java:rSMT()` | Init + simulate + print all in constructor — untestable |
| `genIOrig` unused | `rSMT.java:29` | Dead field |
| Commented-out dead code | `rSMT.java:112` | Old `rSimulate()` left in file |
| `curInst_FPU/B` allocated as size-2 but only `[0]` used | `rSMT.java:105` | Misleading structure |

---

## GUI Hook Design Principle (applies to ALL sub-tasks)

Every sub-task from ST-2 onward must be designed so the GUI can observe and control the simulation without reaching into engine internals. Concretely:

- The `SimulationEngine` exposes a **listener/callback interface** (`SimulationListener`) that fires events on every clock tick: which instructions are in each pipeline slot, current cycle number, current IPC.
- The engine checks a **control interface** (`SimulationControl`) every tick: `isPaused()`, `isSmtEnabled()`, `getTickDelayMs()`. This allows the GUI to toggle rSMT mid-run and set animation speed without restarting the simulation.
- The CLI uses a no-op `SimulationListener` and a static `SimulationControl` (SMT always on, no delay, no pause).
- The GUI wires its live widgets as the `SimulationListener` and its controls as the `SimulationControl`.
- These two interfaces live in `com.rsmt.core` so both `sim` and `gui` packages can depend on them without circular imports.

---

## Sub-Tasks

---

### Sub-Task 0 — GitHub Hard Fork + Repo Setup

**Status:** `[ ] pending`

**Intent:**
Create the `rSMTv2` GitHub repository as a clean hard fork (new independent repo, not a GitHub fork). Initialize it from the current workspace, set origin to the new repo, and commit the original rSMT source as the baseline commit so history is traceable.

**Expected Outcomes:**
- `github.com/007Style/rSMTv2` exists as a public repo.
- The workspace `.git` remote `origin` points to `rSMTv2`.
- Initial commit contains the cloned `rSMT/` source and this plan file.
- `README.md` updated to describe rSMTv2 and link back to the original rSMT repo and IBM patent.

**Todo List:**
1. Create the new GitHub repo: `gh repo create 007Style/rSMTv2 --public --description "Reverse SMT processor simulator v2 — modernized Java 21 hard fork of rSMT"`.
2. Update the git remote `origin` to point to the new `rSMTv2` repo.
3. Write a new `README.md` for rSMTv2: describe the project, reference original rSMT repo, reference IBM patent US8595468, note the Java 21 + Maven + JavaFX stack.
4. Commit everything and push to `main`.

**Relevant Context:**
- Original repo: `github.com/007Style/rSMT`
- GitHub CLI already authenticated (`gh` available)
- This is a hard fork — independent repo, no upstream tracking branch to rSMT

---

### Sub-Task 1 — Project Scaffold (Maven + Java 21)

**Status:** `[ ] pending`

**Intent:**
Create the rSMTv2 Maven project structure. This is the clean-room foundation — no simulation logic yet, just the correct package layout, `pom.xml`, and module structure that all subsequent phases build on.

**Expected Outcomes:**
- `pom.xml` exists with Java 21, JUnit 5, JavaFX 21 dependencies, and Maven Shade plugin declared.
- Source tree at `src/main/java/com/rsmt/{core,sim,gui}/` and `src/test/java/com/rsmt/` exists.
- `SimulationListener` and `SimulationControl` interfaces exist as empty stubs in `com.rsmt.core` (GUI hook foundation).
- `mvn compile` succeeds on an empty skeleton.
- `mvn test` runs (zero tests, none fail).

**Todo List:**
1. Create `pom.xml` at workspace root targeting Java 21, with dependencies: `junit-jupiter`, `javafx-controls`, `javafx-fxml`, and Maven Shade plugin.
2. Create directory structure: `src/main/java/com/rsmt/{core,sim,gui}/` and `src/test/java/com/rsmt/`.
3. Add stub `SimulationListener.java` interface in `com.rsmt.core` (empty — methods added in ST-4).
4. Add stub `SimulationControl.java` interface in `com.rsmt.core` (empty — methods added in ST-4).
5. Add a placeholder `App.java` main class in `com.rsmt` that prints `rSMTv2 starting...`.
6. Verify `mvn compile` succeeds.

**Relevant Context:**
- Original source: `rSMT/src/rsmt/Main.java`, `rSMT.java`, `genInstructions.java`
- New package root: `com.rsmt`
- Sub-packages: `core` (data model + GUI interfaces), `sim` (simulation engine), `gui` (JavaFX)

---

### Sub-Task 2 — Core Data Model (Modern Java 21)

**Status:** `[ ] pending`

**Intent:**
Replace the `rInst` inner class and index-based type detection with a proper, type-safe data model using Java 21 features. This eliminates the root cause of the FP bug and makes the simulation engine clean to write.

**Expected Outcomes:**
- `SimulationConfig` record holds all input parameters (immutable), including `smtEnabled` flag for runtime toggle.
- `Instruction` sealed interface with concrete subtypes: `FxuInstruction`, `FpuInstruction`, `BranchInstruction`, `LoadInstruction`, `StoreInstruction`, `NopInstruction`.
- Each subtype carries its own operands and latency — no `index`-based dispatch.
- `SimulationResult` record holds `rCycles`, `normCycles`, `performanceGain`, counts, per-unit utilization, and stall counts.
- `ClockEvent` record represents a single clock tick snapshot (cycle number, map of slot → instruction, current IPC) — used by `SimulationListener`.
- Unit tests cover: correct type detection, correct latency per instruction type.

**Todo List:**
1. Create `SimulationConfig.java` as a `record` in `com.rsmt.core` with fields: `numInstructions`, `rSmtDelay`, `percentInt`, `percentLoad`, `rSmtAvailPercent`, `rSmtDependsPercent`.
2. Create `Instruction.java` as a `sealed interface` permitting `FxuInstruction`, `FpuInstruction`, `BranchInstruction`, `LoadInstruction`, `StoreInstruction`, `NopInstruction`.
3. Create each instruction subtype as a `record` with fields: `order`, `curClock`, `doneClock`, and type-specific operands.
4. Create `SimulationResult.java` as a `record` with fields: `rCycles`, `normCycles`, `totalInstructions`, `intInstructions`, `smtInstructions`, `ipc`, per-unit utilization map, stall counts (`structuralStalls`, `dataStalls`, `controlStalls`).
5. Create `ClockEvent.java` as a `record` with fields: `cycleNumber`, `slotSnapshot` (map of slot name → Instruction or null), `currentIpc`, `smtActive`.
6. Write unit tests for type detection and latency values.

**Relevant Context:**
- Replaces: `genInstructions.rInst` inner class in `rSMT/src/rsmt/genInstructions.java:121`
- Latency constants: FXU=5, FPU=6, Branch=4, LSU=3, NOP=0
- `ClockEvent` is the data contract between engine and GUI — design it carefully
- Java 21 `record` and `sealed interface` with `permits` clause

---

### Sub-Task 3 — Instruction Generator (Fixed & Modernized)

**Status:** `[ ] pending`

**Intent:**
Rewrite `genInstructions.java` using the new data model. Fix the FP arithmetic bug in `execute()`. Use `ArrayList<Instruction>` and proper generics throughout.

**Expected Outcomes:**
- `InstructionGenerator` class produces a `List<Instruction>` with correct type distribution including Load/Store.
- `execute(Instruction)` correctly computes results for FXU, FPU, LSU instructions (FP bug fixed).
- Seeded randomness works identically to original for same-seed reproducibility.
- Unit tests verify: correct mix ratios, correct FP arithmetic, correct FXU arithmetic, no div-by-zero crash on zero operands.

**Todo List:**
1. Create `InstructionGenerator.java` in `com.rsmt.sim` with a constructor accepting a `long seed`.
2. Implement `generate(SimulationConfig config): List<Instruction>` — produces typed instruction objects using the sealed interface subtypes, respecting `percentInt` and `percentLoad`.
3. Implement `execute(Instruction inst): Instruction` using `switch` on sealed type — fix the FP index bug here.
4. Write unit tests: generate 1000 instructions at 50% int, 20% load → verify ratios; verify FPU results correct.

**Relevant Context:**
- Bug to fix: `genInstructions.java:96` — FP arithmetic block uses wrong index comparisons
- Original: `rSMT/src/rsmt/genInstructions.java`
- Use sealed type `switch` expression instead of index chains

---

### Sub-Task 4 — Simulation Engine (Fixed, Modernized + GUI Hooks)

**Status:** `[ ] pending`

**Intent:**
Rewrite `rSMT.java` as a clean, testable simulation engine. Fix the inverted `rSMT_depends()` logic. Separate simulation from printing. Wire the `SimulationListener` and `SimulationControl` interfaces so the GUI can observe and control the engine in real time without polling or coupling to engine internals.

**Expected Outcomes:**
- `SimulationEngine` class accepts a `SimulationConfig`, a `List<Instruction>`, a `SimulationListener`, and a `SimulationControl`.
- Every clock tick fires `SimulationListener.onClockTick(ClockEvent)`.
- Engine checks `SimulationControl.isPaused()` every tick — if true, spins in a wait loop until unpaused.
- Engine checks `SimulationControl.isSmtEnabled()` every tick — can toggle rSMT mid-simulation.
- Engine calls `Thread.sleep(SimulationControl.getTickDelayMs())` each tick to honor animation speed.
- `rSmtDepends()` logic corrected: returns `true` (hazard) when `rGen <= dependsPercent`.
- All raw `Vector` replaced with `ArrayDeque<Instruction>`.
- Unit tests: correct cycle ordering, dependency fix, listener fires correct number of times.

**Todo List:**
1. Define `SimulationListener` interface in `com.rsmt.core`: `onClockTick(ClockEvent)`, `onSimulationComplete(SimulationResult)`.
2. Define `SimulationControl` interface in `com.rsmt.core`: `isPaused()`, `isSmtEnabled()`, `getTickDelayMs()`.
3. Add `NoOpSimulationListener` (fires nothing) and `StaticSimulationControl` (always-on, no delay) implementations in `com.rsmt.core` for CLI use.
4. Create `SimulationEngine.java` in `com.rsmt.sim` with constructor: `SimulationEngine(SimulationListener, SimulationControl)`.
5. Implement `SimulationResult run(SimulationConfig config, List<Instruction> instructions)`.
6. Implement SMT-ON and SMT-OFF passes with tick loop, listener firing, and control checking.
7. Fix `rSmtDepends()`.
8. Write unit tests.

**Relevant Context:**
- Bug to fix: `rSMT.java:311`
- `SimulationListener` / `SimulationControl` stubs created in ST-1, defined here
- `StaticSimulationControl.getTickDelayMs()` returns 0 for CLI (ludicrous mode)
- GUI will supply its own `SimulationControl` implementation backed by UI controls

---

### Sub-Task 5 — CLI Entry Point + Report

**Status:** `[ ] pending`

**Intent:**
Rewrite `Main.java` as a clean CLI entry point. Move all print logic into a `SimulationReport` class. The CLI wires config → generator → engine → report. When no args are given, launch the JavaFX GUI (stubbed for now).

**Expected Outcomes:**
- `Main.java` is a thin wiring class: parse args → build config → generate instructions → run engine → print report.
- `SimulationReport.print(SimulationResult)` produces output matching original format plus new fields (IPC, unit utilization, stall counts).
- `mvn package` produces a runnable `rSMTv2.jar`.
- Running `java -jar rSMTv2.jar 100 0 50 20 50 20` produces correct output (6 args now: added `percentLoad`).
- Running `java -jar rSMTv2.jar` with no args prints usage (GUI launch wired in ST-8).

**Todo List:**
1. Create `SimulationReport.java` in `com.rsmt.core` with `print(SimulationResult result)`.
2. Rewrite `Main.java` in `com.rsmt` using `NoOpSimulationListener` and `StaticSimulationControl`.
3. Verify Maven Shade plugin produces a fat JAR.
4. Smoke-test: run the JAR, verify performance gain > 100% at 100% availability, 0% depends.

**Relevant Context:**
- Original: `rSMT/src/rsmt/Main.java`
- Default params preserved: 100 instructions, 0 delay, 50% int, 20% load, 50% availability, 20% depends
- CLI arg order: `<numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>`

---

### Sub-Task 6 — Clock-Tick Expansion (More Execution Units)

**Status:** `[ ] pending`

**Intent:**
Expand the simulation model to include a second FPU slot, a Load/Store Unit (LSU), and a dedicated Branch unit. Instruction types added in ST-2 are now fully wired into the engine. This is Phase 2a — still clock-tick based, no pipeline stages yet.

**Expected Outcomes:**
- Simulation supports: FXU×2 (primary + SMT slot), FPU×2, Branch×1, LSU×1.
- `LoadInstruction` and `StoreInstruction` route to LSU and retire after 3 clocks.
- `SimulationResult` reports per-unit utilization (% of cycles each unit was busy).
- `ClockEvent` slot snapshot includes all 6 slots: FXU0, FXU1, FPU0, FPU1, Branch, LSU.
- Unit tests: verify LSU retires correctly; verify dual FPU improves cycle count vs single FPU.

**Todo List:**
1. Add LSU slot (`curInst_LSU`) to `SimulationEngine` issue and execute/retire logic.
2. Wire second FPU slot (`curInst_FPU[1]`) into SMT-ON pass.
3. Add per-unit busy-cycle counters; compute utilization % for `SimulationResult`.
4. Update `ClockEvent` slot snapshot to include all 6 slots.
5. Update `SimulationReport` to print unit utilization table.
6. Update unit tests.

**Relevant Context:**
- Builds on Sub-Tasks 1–5 (must be complete first)
- LSU latency: 3 clocks (configurable via `SimulationConfig` in a future phase)
- `curInst_FPU` in original was size-2 — `[1]` now gets used for the second FPU in SMT-ON

---

### Sub-Task 7 — Pipeline Stage Simulation

**Status:** `[ ] pending`

**Intent:**
Upgrade the engine from a flat clock-tick model to a true 4-stage pipeline: Fetch → Decode → Execute → Retire. Each stage has its own stall conditions. This is Phase 2b.

**Expected Outcomes:**
- Instructions flow through a 4-stage pipeline with per-stage queues.
- Structural hazards (unit busy), data hazards (configurable %), and control hazards (branch stall) are all modeled and counted.
- `SimulationResult` gains: `structuralStalls`, `dataStalls`, `controlStalls`, `ipc`.
- `ClockEvent` gains: `pipelineStage` snapshot (what is in each stage this tick).
- Both SMT-ON and SMT-OFF passes use the pipeline model.
- Unit tests: branch stalls pause fetch; structural hazard when FXU full; IPC within expected range.

**Todo List:**
1. Design pipeline stage data structures in `com.rsmt.sim`: `FetchQueue`, `DecodeQueue`, `ExecuteSlots`, `RetireBuffer`.
2. Implement `PipelineEngine` in `com.rsmt.sim` — per-stage tick loop replacing the flat loop.
3. Implement stall detection: structural (no free execution slot), data (`rSmtDepends`), control (branch in-flight).
4. Add stall counters to `SimulationResult`.
5. Add `ipc` computation (total retired / total cycles).
6. Update `ClockEvent` to include pipeline stage snapshot for GUI animation.
7. Keep `SimulationEngine.run()` as the public API — `PipelineEngine` is an internal impl.
8. Update `SimulationReport` to print IPC and stall breakdown.
9. Write unit tests for each stall type.

**Relevant Context:**
- Builds on Sub-Tasks 1–6 (must be complete first)
- `execBranch` flag in original (`rSMT.java:38`) is the seed of control hazard logic — promote it here
- `ClockEvent.pipelineStage` snapshot is the data the GUI pipeline grid will render

---

### Sub-Task 8 — JavaFX GUI (Live Visualization)

**Status:** `[ ] pending`

**Intent:**
Build a JavaFX desktop UI that provides rich live visualization of the simulation. The user can configure all parameters, watch instructions flow through the pipeline in real time, toggle rSMT on/off mid-simulation, and control animation speed from slow-motion to ludicrous mode. The GUI is grafted onto the engine entirely through the `SimulationListener` / `SimulationControl` interfaces — no internal engine coupling.

**Expected Outcomes:**

*Configuration Panel:*
- Sliders/spinners for all `SimulationConfig` fields (numInstructions, rSmtDelay, %int, %load, %avail, %depends).
- rSMT toggle switch (can be flipped during a running simulation).
- Animation speed selector: 1s / 2s / 5s / 10s / Ludicrous (0ms) per clock cycle.
- Run / Pause / Reset buttons.

*Live Pipeline View (main visualization):*
- A scrollable grid: rows = pipeline slots (FXU0, FXU1, FPU0, FPU1, Branch, LSU), columns = clock cycles advancing in real time.
- Each cell shows the instruction occupying that slot on that cycle as a colored block (color-coded by instruction type).
- Instructions animate left-to-right as clock advances — visible pipeline flow.
- Current cycle counter and live IPC counter update every tick.
- rSMT active/inactive indicator updates instantly when toggled.

*Results / Analytics Panel (updates live):*
- Line chart: IPC over time (updates every N ticks).
- Bar chart: rCycles vs normCycles (final, shown when simulation completes).
- Bar chart: per-unit utilization (FXU0, FXU1, FPU0, FPU1, Branch, LSU) — updates live.
- Pie/bar chart: stall breakdown (structural, data, control) — updates live.
- Performance gain % — shown prominently when simulation completes.

*Style:*
- Dark theme CSS: dark background, monospace font, green instruction blocks for FXU, blue for FPU, yellow for Branch, cyan for LSU, grey for NOP.
- Responsive layout — works at 1280×800 minimum.

**Todo List:**
1. Create `MainApp.java` in `com.rsmt.gui` — JavaFX `Application`, launches main window.
2. Implement `GuiSimulationControl.java` — `SimulationControl` backed by UI toggle/speed controls; thread-safe.
3. Implement `GuiSimulationListener.java` — `SimulationListener` that posts `ClockEvent` updates to the JavaFX Application Thread via `Platform.runLater`.
4. Design `main.fxml` — top-level layout: config panel left, pipeline view center, analytics panel right.
5. Implement `PipelineGridView.java` — custom JavaFX `GridPane` that renders the scrollable pipeline slot × cycle grid; adds a new column each tick.
6. Implement `AnalyticsPanel.java` — hosts the IPC line chart, cycles bar chart, utilization bar chart, stall pie chart; updates on each `ClockEvent`.
7. Implement `SimulationController.java` — JavaFX controller: wires config controls → `SimulationConfig`, starts `Task<SimulationResult>` on background thread, plumbs listener and control.
8. Implement animation speed: `GuiSimulationControl.getTickDelayMs()` returns 1000/2000/5000/10000/0 based on speed selector.
9. Implement rSMT mid-run toggle: `GuiSimulationControl.isSmtEnabled()` reads live from the toggle switch.
10. Wire `Main.java` to launch `MainApp` when no CLI args provided.
11. Apply dark theme `styles.css`.
12. Manual smoke-test: run GUI, set 500 instructions, 50% int, 50% avail, 2s/tick — watch pipeline animate, toggle rSMT, observe IPC chart update.

**Relevant Context:**
- Builds on all previous sub-tasks
- `SimulationListener` / `SimulationControl` defined in ST-4 — GUI just provides implementations
- Engine already calls `Thread.sleep(getTickDelayMs())` and checks `isPaused()` / `isSmtEnabled()` — GUI hooks are free
- Use `javafx-maven-plugin` for `mvn javafx:run`
- JavaFX `Task<SimulationResult>` for background thread; `Platform.runLater` for UI updates from listener

---

## Out of Scope for rSMTv2 (Future Phase)

**Out-of-Order Execution** — register renaming, reorder buffer (ROB), Tomasulo algorithm. This would make rSMTv2 a research-grade microarchitecture simulator. Left as a clearly-marked future phase once the v2 pipeline model is stable and well-tested. The `PipelineEngine` can be subclassed or replaced without changing the `SimulationEngine` public API.

---

## Implementation Order

```
ST-0 (GitHub repo) → ST-1 (Maven scaffold) → ST-2 (data model) → ST-3 (generator)
  → ST-4 (engine + GUI hooks) → ST-5 (CLI)
    → ST-6 (more units) → ST-7 (pipeline stages)
      → ST-8 (JavaFX GUI)
```

Each sub-task must pass its unit tests before the next begins.
