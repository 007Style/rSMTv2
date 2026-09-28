# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Coding Rules (Non-Obvious)

- **Never modify `rSMT/`** — it is the read-only original baseline. All new code goes under `src/`.
- **`SimulationEngine` constructor takes `SimulationListener` and `SimulationControl`** — never instantiate it without both. Use `NoOpSimulationListener` and `StaticSimulationControl` for headless/test use.
- **`ClockEvent.slotSnapshot` map keys must be exactly**: `FXU0`, `FXU1`, `FPU0`, `FPU1`, `Branch`, `LSU` — the GUI `PipelineGridView` looks up these exact strings. A typo here silently drops the slot from the visualization.
- **`rSmtDepends()` must return `true` to BLOCK issue** (hazard present) — the original had this backwards. Double-check the boolean polarity any time you touch hazard logic.
- **`Instruction` subtypes are immutable records** — `curClock` progression is modeled by replacing the record in the slot array, not mutating it.
- **`SimulationControl.isSmtEnabled()` is checked per tick** — the SMT-ON/OFF pass split in the original is gone; a single pass reads `isSmtEnabled()` each cycle to decide whether to fill `FXU1`.
- **`switch` on sealed `Instruction` must be exhaustive** — the compiler enforces this, but if you add a new `Instruction` subtype, every `switch` site breaks at compile time (intentional — don't suppress).
- **Fat JAR produced by Maven Shade plugin** — do not use Assembly plugin. Shade is already in `pom.xml`.
- **JavaFX modules must be on the module path**, not classpath — the `javafx-maven-plugin` handles this for `mvn javafx:run`; the Shade plugin manifest must set `--add-modules` in `MANIFEST.MF` for the runnable JAR.
- **`Thread.sleep(getTickDelayMs())` in the engine tick loop** — wrap in try/catch and re-interrupt the thread: `Thread.currentThread().interrupt()`. Do not swallow the `InterruptedException`.
- **Unit tests must use a fixed seed** (e.g. `42L`) for `InstructionGenerator` — non-seeded tests produce flaky results.

## Single Test Command

```bash
mvn test -Dtest=ClassName#methodName
```
