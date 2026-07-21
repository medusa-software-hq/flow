# M4 demo runbook — Claude Agent engine

The M4 acceptance demo (from `plan/m4/design/00-m4-definition.md`) proves the
**Claude Agent** engine end to end. Following the M2.5 rails, every demo step
maps to an **automated test** wherever one can carry it; only the genuinely
once-off, live-deployed checks are done by hand, and each of those is
⚠️-flagged with a linked "automate this" follow-up.

| # | Demo item | Automated coverage | Status |
|---|-----------|--------------------|--------|
| 1 | Manifest-less arbitrary repo → PR | `HrsClaudeTaskCompleter` manifest-less test (A4): no gate, prompt augmented to run the repo's own checks, one run → Success | unit ✅ + ⚠️ live (below) |
| 2 | Gate + bounce on a manifest repo | A4 tests: gated-green, gated-red-recovered (the resume prompt carries diagnostics), bounce-exhausted, broken-baseline; **live** in the A7 loop (the gradle fixture is gated) | ✅ |
| 3 | Full M2 auto cycle with `flow:engine=claude` | A2 label-stamping (`flow:engine=claude` → session engine) unit + component tests; the reconciler/merge cycle is unchanged from M2 | unit/component ✅ + ⚠️ live (below) |
| 4 | Engine selection / capability filter | A2 claim-filter tests (a builtin-only worker never claims a claude session and doesn't block behind one; unspecified → either); A6 dispatch tests (session routes to the completer for its engine, unspecified → default) | ✅ |
| 5 | Hermetic loop green with `--engine claude` | **`Check hermetic loop (claude)`** (A7) — the shipped worker runs the real `claude` CLI through the gated gradle fixture to a PR. **Validated:** `LOOP_RESULT fixture=gradle-claude outcome=pass retried=false`. | ✅ **live in CI** |
| 6 | Cost display + budget-cap legibility | A5 cost tests (RunCost event → `total_cost_usd`, web cost line + list column); A3/A4 cap-trip → `HrsClaudeEngineException` naming the cap | ✅ |

## ⚠️ Hand-tests (need a hosted claude worker — Path B)

Items 1 and 3's *live, deployed* form — an issue on a real repo, labeled
`flow:ready` + `flow:engine=claude`, going to a merge-able PR with no manual
steps but review — requires a **worker running the claude engine against the
deployed stack**. Today that's an operator running the worker locally with their
subscription; the always-on hosted version is **Path B** (`ms-workload`). Until
then:

- ⚠️ **Hand-test:** run a worker with `FLOW_WORKER_ENGINES=claude` +
  `FLOW_CLAUDE_AUTH=personal` + `CLAUDE_CODE_OAUTH_TOKEN` against staging, label
  a `project.yaml`-less sandbox issue `flow:ready` + `flow:engine=claude`, and
  confirm it reaches a merge-able PR. *Automate:* the M5 loop tier
  (`LoopTierTest`, see [`testing.md`](testing.md)) already drives the real
  session→worker→PR loop against staging; point it at a `claude`-engine session
  (the engine is a per-session parameter) once the staging worker declares the
  `claude` engine. The M2.5-09 nightly this used to reference is retired —
  superseded by the per-promotion loop tier.
- ℹ️ **Hand-test:** in the web app, pick "Claude Agent" on a manifest-less repo
  and confirm the action feed + banner render and the cost line reads clearly
  (A5 covers the rendering in vitest; the end-to-end UX is eyeball-only).

## What's proven without any hand-test

The engine itself — driver, gate, bounce, cost, capability routing, auth-env
construction — is covered by unit/component tests, and **the whole worker→engine
loop runs live in CI** (item 5). The gap is purely "the deployed stack with a
hosted claude worker," which is Path B, not Path A.
