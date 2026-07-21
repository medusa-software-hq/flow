# Testing Flow — the layer map

> **"How do I know the cloud app works?"** — every step of the old manual
> acceptance demos is covered by an automated test, and the promotion pipeline
> runs the relevant ones before prod. You never have to click through a runbook
> to gain confidence; you read this page and look at green checks. The demo
> runbooks (`docs/m2-demo-runbook.md`, `docs/m4-demo-runbook.md`) survive only
> as narrated walkthroughs, not as the source of truth.

Flow is verified in **five layers**, cheapest/fastest first. Each layer runs at
a defined point and needs a defined environment:

| Layer | What it exercises | Where it lives | Runs when | Needs |
|---|---|---|---|---|
| **Unit** | Pure logic (stores, mappers, config, poll/registration loops) | each module's `src/test` | every PR (`check`) + `build` | nothing |
| **Component** | The control plane in-process: real Armeria server + gRPC client, auth decorators, `WorkerAuthorizer`, state-machine guards, webhook route, reconcile against the GitHub stub | `backend/api/impl/shared/src/test` (`*_serverTests`, `*_componentTests`) | every PR (`check`) | nothing (GitHub is a local stub) |
| **Hermetic loop** | The **whole system in one JVM**: real control plane + the *shipped* worker jar as a subprocess + real git + real engine on cheap models; only GitHub is a local stub | `e2e/` (`HermeticLoop*`, `HermeticSadPaths*`) | `check-hermetic-loop.yml`, `check-sad-paths.yml` (PR, path-gated) | `OPENROUTER_API_KEY` (cheap models) |
| **Smoke tier** | A **deployed** staging API + SPA, black-box: health, session round-trip, worker RPC cycle, reconcile, webhook rejection, SPA reachable | `system-tests/` `@Smoke` | promotion gate (staging→prod) + daily | deployed staging + WIF creds; **no worker** |
| **Loop tier** | The **full real loop** against deployed staging: a session claimed by staging's own admin-run worker, completing to a PR on the sandbox org | `system-tests/` `@Loop` | promotion gate (opt-in) + daily | deployed staging + WIF + a **live worker** + model budget |

The first three run on **every PR** and gate the merge. The last two run against
the **deployed** environment and gate **promotion to prod** (`check-staging-smoke.yml`,
wired into `promote-api.yml` / `promote-web-spa.yml`).

## Mapping: manual acceptance-demo steps → covering test

The M1/M2 manual demos (`docs/m2-demo-runbook.md`) proved these steps by hand.
Each is now covered:

| Demo step | Covered by | Layer |
|---|---|---|
| CLI turns a task + worktree into a materialized change | `HermeticLoop_integrationTests` (shipped jar, real engine) | Hermetic |
| A user creates a session (`CreateSession`) | `SmokeTierTests` (round-trip) + `SessionServiceImpl_serverTests` | Smoke + Component |
| `GetSession` / `ListSessions` reflect state | `SmokeTierTests` (round-trip) + `SessionServiceImpl_serverTests` | Smoke + Component |
| The reconciler picks a `flow:ready` issue and creates a session | `ReconcileAgainstStub_componentTests`, `ReconcileServiceImpl_serverTests` | Component |
| A worker claims the oldest session (`ClaimNextSession`, SKIP LOCKED) | `WorkerServiceImpl_serverTests` + `SmokeTierTests` (worker cycle) | Component + Smoke |
| Worker heartbeats / reports events; lost-heartbeat expiry | `WorkerServiceImpl_serverTests`, `InMemorySessionStore` tests | Component |
| **Worker completes a session and opens a PR** | **`LoopTierTest`** (real worker → real PR on the sandbox) | **Loop** |
| Worker registers with the fleet; liveness is queryable | `WorkerServiceImpl_serverTests` (register/list), `WorkerLivenessPreflightTest` | Component + Loop |
| Control plane merges the PR + closes the issue; dependent unblocked | `ReconcileAgainstStub_componentTests` (full pipeline vs the stub) | Component |
| Webhook rejects an unsigned event; accepts a valid one | `WebhookRoute_componentTests` + `SmokeTierTests` (bad-sig 401) | Component + Smoke |
| Publish-crash / bad-baseline / model-gives-up sad paths | `HermeticSadPaths_integrationTests` | Hermetic |
| SPA serves + deep links resolve | `SmokeTierTests` (SPA reachable) | Smoke |

**Accepted gaps** (no per-promotion automated coverage, by decision):

- **The full reconcile *cycle* (merge → close → dependent-pick) against real
  staging.** The loop tier does the *direct* session→PR loop per promotion; the
  full M2 cycle is component-tested against the stub (above) and can be run
  ad hoc via the loop module. Rationale in `LoopTierTest` — nightly-grade
  full-cycle assertions are overkill per-promotion.
- **Browser E2E of the SPA** (click-through). Optional; tracked as M2.5-11.

## Running each layer locally

```bash
# Unit + component — no credentials:
./gradlew check          # ktfmt + detekt + all unit/component tests
./gradlew build          # the above + assemble

# Hermetic loop (cheap model credits):
OPENROUTER_API_KEY=sk-or-... ./gradlew :e2e:integrationTest

# Smoke tier — against deployed staging (WIF/ADC; see below):
API_URL=https://api.flow-baseline-staging.medusa.software \
WORKER_SA_EMAIL=flow-worker@<staging-project>.iam.gserviceaccount.com \
  ./gradlew :system-tests:systemTest -PsystemTestTags=smoke

# Loop tier — needs a live staging worker + a sandbox GitHub token:
API_URL=... WORKER_SA_EMAIL=... SANDBOX_GH_TOKEN=$(gh auth token) \
  ./gradlew :system-tests:systemTest -PsystemTestTags=loop
```

Auth for the deployed layers mirrors the worker (`WrkGrpcApiClient`): a Google
ID token (audience = the API URL) from Application Default Credentials, worker-SA
impersonated when `WORKER_SA_EMAIL` is set. On a laptop, `gcloud auth
application-default login` (as a `flow-admins@` member, who can impersonate the
worker SA); in CI the WIF step provides it. See
[`../system-tests/README.md`](../system-tests/README.md).

## The promotion gate, and the loop-tier flip criteria

`check-staging-smoke.yml` is the gate between the staging and prod deploys. It
always runs the **smoke tier** (no worker needed — a worker outage never blocks
an API-only fix). It runs the **loop tier** only when the **`FLOW_LOOP_GATE`**
repo/environment variable is `enabled`.

**Flip `FLOW_LOOP_GATE=enabled`** once both hold:

1. **A persistent, supervised staging worker** is guaranteed — an admin runs
   `ms-workload worker run --profile flow-worker-staging` under a supervisor that
   survives reboots (not an ad-hoc process). See [`../worker/README.md`](../worker/README.md).
2. **Transient model flakes are absorbed** — the loop drives a real LLM, which
   can fail transiently (`finish_reason=error`, cut-short responses). Until the
   loop tier retries such failures, a flake would redden the gate; the interim
   policy is **re-run the failed `loop` job** (`gh run rerun <id> --failed`),
   exactly as the retired nightly's transient-flake guidance.

While `FLOW_LOOP_GATE` is unset, promotions ride the smoke tier and the loop
tier is skipped (never parks prod). The daily scheduled run follows the same
flag.

## Flake-budget policy

- **Unit / component / hermetic** are expected **deterministic**. A flake is a
  bug — fix it, don't retry. (The one environmental subtlety — a fixture git
  repo's default branch — is pinned; see `flow-home/CLAUDE.md`.)
- **Smoke tier** is deterministic against a healthy deploy; a failure means the
  deploy is broken (its whole purpose). Do not retry-to-green — investigate.
- **Loop tier** has an irreducible non-determinism (real models). It **retries
  itself once, in-test**: a session that FAILs with a transient signature
  (model cut-short / `finish_reason=error` / rate limit — see
  `LoopFailureClassifier`) is re-run with a fresh session; a *deterministic*
  failure (real session failure, no PR, PR not open) fails immediately, and a
  transient flake that doesn't clear on the retry is treated as real. So a
  single provider hiccup no longer reddens the gate. If the gate still goes red
  on a *transient* failure that slipped the classifier, the fallback is **one**
  manual `gh run rerun <id> --failed`; a second failure is a real regression —
  do not re-run past it. Escalation: if the loop tier flakes more than ~1 run in
  5 even with the retry, it is not fit to gate — disable `FLOW_LOOP_GATE`,
  widen the classifier or investigate, then re-enable.

## Sandbox reset

The loop tier and the (retired) nightly run against the sandbox fixture repo
`medusa-software-test-hq/flow-sandbox-fixture`. The loop tier is self-cleaning
(unique per-run branch/session; closes its PR + deletes its branch in a
`finally`). To reset the fixture to its canonical baseline by hand (e.g. after
an interrupted run left branches/PRs behind):

```bash
GH_TOKEN=<sandbox-admin-or-app-token> ./sandbox/seed.sh
```

`seed.sh` force-pushes the baseline `main`, deletes leftover `flow/*` branches
and their PRs, and recreates the `flow:ready` issue chain. It is idempotent.
See [`../sandbox/README.md`](../sandbox/README.md).
