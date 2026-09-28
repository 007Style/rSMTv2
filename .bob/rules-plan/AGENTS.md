# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Architectural Constraints (Non-Obvious)

- **`com.rsmt.core` must have zero dependencies on `sim` or `gui`** — it is the shared foundation. Both `sim` and `gui` depend on `core`. A circular dependency here breaks the GUI-hook design entirely.
- **`SimulationEngine` is not split into SMT-ON / SMT-OFF subclasses** — there is one engine that reads `SimulationControl.isSmtEnabled()` each tick. The original two-pass design (rSimulate / normSimulate) is replaced by a single pass with a live toggle. Plan both passes as one loop, not two separate methods.
- **`PipelineEngine` is hidden behind `SimulationEngine.run()`** — callers never reference `PipelineEngine`. This is the extension point for future out-of-order execution (phase 3+).
- **Out-of-order execution is explicitly out of scope for rSMTv2** — do not design data structures that assume a ROB or register renaming. The `PipelineEngine` should be subclassable but not pre-baked for OOO.
- **`ClockEvent` is append-only by design** — new fields can be added (e.g. `pipelineStage` in ST-7) but existing fields must not be renamed or removed. The GUI binds to field names.
- **JavaFX GUI thread model**: `SimulationEngine` runs on a `Task` background thread. `GuiSimulationListener.onClockTick()` must always dispatch to the JavaFX Application Thread via `Platform.runLater()` — direct UI updates from the engine thread will cause silent rendering failures or exceptions.
- **`GuiSimulationControl` must be thread-safe** — the engine reads it from a background thread; JavaFX controls write it from the UI thread. Use `volatile` fields or `AtomicBoolean`/`AtomicInteger`.
- **Animation speed selector maps to exact ms values**: 1s=1000, 2s=2000, 5s=5000, 10s=10000, Ludicrous=0. These are the contract between the GUI speed control and `getTickDelayMs()`.
- **Sub-tasks must be completed in order** (ST-0 → ST-1 → … → ST-8) — each sub-task's unit tests are a gate for the next. Do not plan parallel implementation of ST-6 and ST-7.
- **rSMTv2 is a hard fork** — the workspace `.git` remote must point to `github.com/007Style/rSMTv2`, not the original `rSMT` repo. There is no upstream tracking branch.
