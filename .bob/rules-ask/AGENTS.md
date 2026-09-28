# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Documentation Context (Non-Obvious)

- **`rSMT/` is the original 2009 source** — it is evidence for IBM Patent US8595468. Treat it as reference documentation, not live code. The README.md in the root describes rSMTv2, not rSMT.
- **The plan file is `rSMTv2-plan.md`** — it is the canonical spec. Sub-task status fields (`[ ] pending` / `[x] done`) are the source of truth for what has been implemented. Read it before answering any question about project scope.
- **`ClockEvent` is the primary integration contract** — when answering questions about how the GUI receives data from the engine, this record is the answer. Its `slotSnapshot` keys (`FXU0`, `FXU1`, `FPU0`, `FPU1`, `Branch`, `LSU`) are the pipeline slot names used everywhere.
- **"Ludicrous mode"** is the project's name for `getTickDelayMs() == 0` (no sleep between ticks) — the terminology appears in user-facing UI labels and in the plan.
- **The original `rSMT_depends()` bug** (inverted polarity) is a known issue documented in both `rSMTv2-plan.md` and `AGENTS.md` — it is not a discovery to re-investigate.
- **6 CLI args in rSMTv2 vs 5 in rSMT** — `%load` was added. Any question about CLI usage should reference the 6-arg signature: `<numInst> <rSmtDelay> <%int> <%load> <%rSmtAvail> <%depends>`.
