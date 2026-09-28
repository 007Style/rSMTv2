# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Project Identity

rSMTv2 is a **hard fork** of `github.com/007Style/rSMT` (IBM Patent US8595468). The `rSMT/` directory is the read-only original source baseline — do not modify files under it. All new code lives under `src/`.

## Stack

- Java 21 LTS, Maven, JUnit 5, JavaFX 21
- Package root: `com.rsmt`
- Sub-packages: `com.rsmt.core` (data model + GUI interfaces), `com.rsmt.sim` (engine), `com.rsmt.gui` (JavaFX)

## Build & Run Commands

```bash
# Compile
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=SimulationEngineTest

# Run a single test method
mvn test -Dtest=SimulationEngineTest#testDependencyFix

# Package fat JAR (Maven Shade plugin)
mvn package

# Run CLI
java -jar target/rSMTv2.jar <numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>

# Run with defaults (100 inst, 0 delay, 50% int, 20% load, 50% avail, 20% depends)
java -jar target/rSMTv2.jar

# Run JavaFX GUI (no CLI args triggers GUI launch)
mvn javafx:run
```

## Critical Architecture Rules

- **`SimulationListener` / `SimulationControl`** are in `com.rsmt.core` — both `sim` and `gui` depend on `core`, never the reverse. Do not put these interfaces in `sim` or `gui`.
- **`SimulationEngine` must check `SimulationControl` every clock tick** — `isPaused()`, `isSmtEnabled()`, `getTickDelayMs()`. This enables GUI mid-run toggle and animation speed without restarting.
- **`SimulationEngine` must fire `SimulationListener.onClockTick(ClockEvent)` every tick** — the GUI pipeline grid depends on receiving every single tick event in order.
- **`ClockEvent` is the GUI data contract** — its `slotSnapshot` map must use these exact slot name keys: `FXU0`, `FXU1`, `FPU0`, `FPU1`, `Branch`, `LSU`. The GUI renders by these names.
- **CLI uses `NoOpSimulationListener` and `StaticSimulationControl`** — `StaticSimulationControl.getTickDelayMs()` returns 0 (ludicrous speed). Never add print statements to the engine itself.
- **`Main.java` launches JavaFX `MainApp` when called with zero CLI args** — CLI mode when args present.
- **`PipelineEngine` is an internal implementation detail** — always keep `SimulationEngine.run()` as the only public API. Never let callers reference `PipelineEngine` directly.

## Known Bugs in Original Code (rSMT/ baseline — fixed in rSMTv2)

| Bug | Original location | Fix |
|---|---|---|
| `rSMT_depends()` inverted | `rSMT.java:311` | Return `true` (hazard) when `rGen <= dependsPercent` |
| FP arithmetic never runs | `genInstructions.java:96` | FP block used index 0–3 instead of 4–7 |

## Java 21 Conventions Used in This Project

- `SimulationConfig`, `SimulationResult`, `ClockEvent`, and all `Instruction` subtypes are **`record`s** — immutable, no setters.
- `Instruction` is a **`sealed interface`** with `permits FxuInstruction, FpuInstruction, BranchInstruction, LoadInstruction, StoreInstruction, NopInstruction`. Use `switch` pattern matching, never `instanceof` chains.
- Never use raw `Vector` or unchecked casts — use `ArrayDeque<Instruction>` for issue queues, `List<Instruction>` for instruction streams.

## Latency Constants (do not change without updating tests)

| Unit | Latency |
|---|---|
| FXU | 5 clocks |
| FPU | 6 clocks |
| Branch | 4 clocks |
| LSU | 3 clocks |
| NOP | 0 clocks |

## CLI Arg Order (6 args in rSMTv2, was 5 in rSMT)

`<numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>`

The extra `%load` arg is new in v2 — do not use the original 5-arg signature anywhere.

## Plan File

Full project plan: `rSMTv2-plan.md` — read it before starting any sub-task. Each sub-task has an explicit status field that must be updated to `[x] done` when complete.
