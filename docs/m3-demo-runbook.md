# M3 leader/assistant engine — integration tests & parity demo (M3-10)

Evidence that the leader/assistant engine (`ENGINE_LEADER`, see [`engines.md`](engines.md)'s
sibling engines) works against real models, and a first read on its economics — the evidence
base referenced by the M3-10 backlog item for eventually promoting it off `FLOW_WORKER_ENGINE=leader`
and into normal engine selection.

## What exists

All of the below live in `engine:harness`'s `integrationTest` source set
(`engine/harness/src/integrationTest/kotlin/software/medusa/flow/harness/leader/`), following the
same conventions as the existing builtin-engine integration test
(`HrsProperFrontlineAiSystem_integrationTests`): gated on `OPENAI_API_KEY` via `assumeTrue` (skipped,
not failed, when unset), real models, no mocking of the model itself.

- **`HrsLeaderTaskCompleter_integrationTests`** — runs the *full* `HrsLeaderTaskCompleter.completeTask`
  (manifest load, initial gate, the leader/assistant turn grammar, final gate) against a small fixture:
  a single Lua file with a deliberately broken Fibonacci recursion, reviving the `harness-ng-1`
  branch's orphaned "run the result" idea (the same fixture shape the builtin engine's own
  integration test already uses). After the run, the model's actual output is independently
  re-executed in an embedded Lua interpreter and checked for `fib(7) == 13` — not just "the gate
  passed," which the model's own edits could in principle game.

  It also asserts the turn grammar from a recording observer attached to the run:
  - **No leader micro-moves** — every `observeAgentAction` fires while a delegation is open; the
    leader is structurally unable to act outside of `Delegate`/`Stop`, and this is the regression net
    for that.
  - **First delegation is exploratory** — the first delegation's report touches no files.
  - **Exposure buffer non-empty before the first patch-bearing delegation** — some delegation prior
    to the first one that reports touched files also reports a non-blank `bufferChanges`.

  These three are real-model assertions, so they carry a small amount of inherent flakiness (unlike
  the token-ratio check, they depend on model behavior, not just arithmetic); if they prove flaky in
  practice the fix is a firmer instruction in the fixture task, not a wider assertion.

- **`EnginesParity_integrationTests`** — the parity run: the same M1/M2-shaped demo-fixture task
  (change a greeting from `Hello` to `Goodbye`, the same two-file-edit shape as `e2e`'s
  `LoopFixture.gradle`) run to completion on **both** the builtin and leader/assistant engines against
  real models, in one process. This is a lightweight in-process comparison — an in-memory physical
  workspace and a text-comparison gate standing in for the fixture's real Gradle `verifyGreeting`
  check — not the full hermetic e2e loop (`e2e`'s `HermeticLoop_integrationTests` /
  `HermeticLoop_claude_integrationTests`), which does not yet have a leader-engine leg (the
  reconcile-driven pipeline always stamps `Engine.Claude` today; adding a leader shadow session there
  is future work, not part of this milestone).

## Reading the output

Each run prints one machine-readable line per role and per engine:

```
PARITY_RESULT fixture=greeting engine=builtin outcome=success wallTimeMs=41823
PARITY_RESULT fixture=greeting engine=leader outcome=success wallTimeMs=68210
PARITY_COST fixture=greeting engine=builtin role=builtin_frontline calls=3 promptTokens=... completionTokens=... totalTokens=...
PARITY_COST fixture=greeting engine=builtin role=builtin_expert calls=1 promptTokens=... completionTokens=... totalTokens=...
PARITY_COST fixture=greeting engine=leader role=leader calls=2 promptTokens=... completionTokens=... totalTokens=...
PARITY_COST fixture=greeting engine=leader role=assistant calls=6 promptTokens=... completionTokens=... totalTokens=...
```

The **token-ratio sanity check** — leader ≪ assistant volume — is asserted directly in both test
classes: the leader (the expensive, capable-tier role) must total fewer tokens than the assistant
(the cheap-tier role that does the actual tool-calling work) over the run. This is the headline
economics signal for the milestone: the expensive role should be a small fraction of total spend.

## Compaction (story 07)

Not exercised by a real-model run here: chunk boundaries are 3/8 delegations apart
(`HrsChunkConfig.default`), and both fixtures above are trivial enough to routinely finish in fewer
delegations than that — forcing a real run to cross a boundary would mean inflating the task well
past what's needed to prove the engine works, for a real-money cost. It is exercised instead as a
unit-level rehearsal (fakes, no real model, deterministic) in `HrsLeaderTaskCompleter_tests`' chunk-close
tests, which directly assert `observeCompaction` fires at small- and big-chunk boundaries.

## Running it

```
OPENAI_API_KEY=... ./gradlew :engine:harness:integrationTest --tests "software.medusa.flow.harness.leader.*"
```

Like the existing `engine:harness` integration tests, this is not currently wired into a CI workflow
(no `.github/workflows/*.yml` runs `:engine:harness:integrationTest` today) — it's run locally /
on demand, same as `HrsProperFrontlineAiSystem_integrationTests` already was before this milestone.
Wiring a scheduled or PR-triggered CI job for this suite (mirroring `check-hermetic-loop.yml`'s
pattern) is a reasonable follow-up once the leader engine is closer to promotion, not a blocker for
this milestone's evidence-gathering goal.
