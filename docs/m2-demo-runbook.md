# M2 acceptance-demo runbook

Proves the auto-mode acceptance demo (from the M2 definition) on the deployed
stack. This is a **collaborative walk**: the control plane (API,
reconciler, scheduler, web app) runs in the cloud, but the demo needs a human to
run the local worker and to merge PRs.

## Prerequisites

- The deployed stack (this repo's `trunk/baseline3`), reachable at
  `https://api.flow-baseline.medusa.software`.
- The GitHub App installed on the demo repo, **and its webhook secret configured**
  (see [../backend/api/infra/github-webhook.md](../backend/api/infra/github-webhook.md))
  if you want to exercise the webhook path. Steps work on scheduler cadence
  (~3 min) without it.
- A local worker: `flow work`, configured per [../worker/README.md](../worker/README.md),
  running against the deployed API. This is what turns picked sessions into PRs.
- `gh` authenticated with access to the demo repo.

## Demo repo setup

Repo: **`medusa-software-hq/flow-worker-fixture`** — a small Gradle project with
a valid `project.yaml` and a green baseline.

1. **Merge-PR workflow.** Add `.github/workflows/merge-pr.yml` on the default
   branch so merges produce a merge-commit check the reconciler can gate on (the
   merge gate distinguishes "no workflows" from "pending"):

   ```yaml
   name: Merge PR
   on:
     push:
       branches: [main]
   jobs:
     verify:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - run: ./gradlew build
   ```

2. **The A ← B ← C chain.** Three issues, each labeled `flow:ready`, whose tasks the
   engine reliably completes (small, self-verifying changes), wired with native
   dependencies:
   - B **blocked by** A, C **blocked by** B (Issue → Relationships → *Blocked by*).
   - All three labeled `flow:ready`.

   Start from a clean slate (no open `flow:*` labels, A/B/C open and unblocked in
   graph order).

## The seven steps

Watch state three ways: GitHub (labels/PRs/comments), the web app
(**Pipelines** and **Sessions**), and the API logs (`reconcile start`/summary).

| # | Action | Expected |
|---|--------|----------|
| 1 | Wait for a reconcile (webhook or the ~3-min tick). | **Only A** is picked (B, C blocked). A gets `flow:in-progress`; a session with an issue link appears in **Sessions**; **Pipelines** shows A `IN_PROGRESS`. |
| 2 | Let the local worker run A's session. | A PR appears: branch `flow/issue-<A>`, title = issue title, body has `Refs #A` (no `Closes`). Pipeline → `PR_OPEN`; label → `flow:pr-open`. **No second issue is picked** (repo mutex). |
| 3 | Merge A's PR; let *Merge PR* run green. | Reconciler closes **A** with an annotation comment linking the PR + session. Pipeline → `DONE`; `flow:*` labels gone from A. |
| 4 | Wait for the next reconcile. | **B** is picked (now unblocked) with no human step. Webhook makes this near-immediate; the scheduler alone would also get there. |
| 5 | Force B's session to **fail** (e.g. a task the baseline can't satisfy). | Pipeline → `FAILED`, `flow:failed` on B, failure summary commented + shown in **Pipelines**. Repo **stopped** — **C is not picked**. Click **Clear** in **Pipelines** → B becomes pickable and is re-picked next cycle. |
| 6 | Submit a **manual** session in the web app (any repo). | Behaves exactly as M1: no issue badge, `flow/session-<id>` branch, plain PR body. Auto mode is unaffected. |
| 7 | **Kill the control plane** (or a worker) mid-cycle, then let it restart. | Next reconcile **converges**: outbox drains, transitions re-derive from PR/merge state, nothing lost or duplicated (at worst a duplicated annotation comment). |

## Also verify

- **Webhook-off degradation.** Disable the App webhook (or leave its secret at
  the placeholder). Repeat steps 1–4: everything still happens, just on the
  ~3-min scheduler cadence instead of within seconds. This is the correctness
  backstop doing its job.
- **Rate-limit headroom.** At demo scale the reconciler makes a handful of
  GitHub calls per repo per cycle (one candidate search + a few reads/writes),
  well under the App installation's 5,000 req/hr — cross-check against the M2
  GitHub-API notes. The outbox coalesces writes and backs off, so a burst can't
  blow the budget.

## Done when

All seven steps pass on the deployed environment with the local M1 worker, and
an issue author can drive auto mode from [auto-mode.md](auto-mode.md) alone.
