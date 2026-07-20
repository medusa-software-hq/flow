# Nightly real-stack run

One real M2-style loop, nightly, against the **deployed staging stack** and the **sandbox GitHub
org** — production model wiring, real money, bounded. It is the only layer that catches two classes
of regression nothing else does: **"GitHub changed under us"** and **"the model's behaviour
drifted."** Everything below staging runs for real: a real worker process, real Google-ID-token
auth, real `github.com`, real OpenRouter models.

- Workflow: [`.github/workflows/nightly-real-stack.yml`](../.github/workflows/nightly-real-stack.yml)
  (`schedule: 0 4 * * *` + `workflow_dispatch`).
- Orchestrator: [`nightly/real-stack.sh`](real-stack.sh).

## The loop it drives

```
reset sandbox fixtures (sandbox/seed.sh)
  → start a real worker in the runner (staging API, worker SA, sandbox App token, real models)
  → staging's reconcile scheduler (every 3 min) creates a session for issue A
  → worker completes A, opens a PR
  → control plane merges the PR + closes issue A
  → the blocked dependent (issue B) is picked
```

Assertions run against **both** the staging API (gRPC-as-JSON, worker-SA ID token — the
`smoke/smoke.sh` pattern) and the **GitHub API** (`gh`, sandbox App token). Each stage is named, has
its own timeout, and aborts early if the worker process dies or the AI leg FAILs the session. The
worker is always torn down (SIGTERM → SIGKILL) via an EXIT trap, and the job has a hard
`timeout-minutes`.

## Identities & credentials

Three distinct GitHub actors run in one loop; only two need a CI credential:

| Actor | Identity | Credential |
|---|---|---|
| Control plane (reconcile/merge/close) | `medusa-flow-test` App | server-side on staging — no CI credential |
| Worker (clone/push/open PR) + reset | **`medusa-flow-nightly`** App (Client ID `Iv23liKTj6dtBHWRnzeg`) | short-lived installation token minted in-workflow |

The dedicated **`medusa-flow-nightly`** App (owned by `medusa-software-test-hq`, installed only on
`flow-sandbox-fixture`, permissions **Contents / Issues / Pull requests: Read & write**) keeps CI's
blast radius off the control-plane App's key. The workflow mints an installation token from it via
`actions/create-github-app-token`.

The **worker's** own auth to the staging API is a Google ID token: the workflow wraps the CI/CD SA's
ADC in an impersonated-SA credential targeting the worker SA (reusing story 08's `tokenCreator`
grant — no new infra), so the token is worker-class and passes the `WORKER_SA_EMAILS` allowlist.

### Required setup (one-time, human)

1. **App permissions & install** — confirm `medusa-flow-nightly` has Contents/Issues/PRs write and is
   installed on `medusa-software-test-hq/flow-sandbox-fixture`.
2. **Secret** — `terraform apply` `.github/config` (adds the `NIGHTLY_APP_PEM_CONTENT` secret name),
   then set its value to the App's private key (the raw GitHub-issued PKCS#1 `.pem`, used as-is).

Until the secret holds the real PEM, runs fail fast at the token-mint step (and open the failure
issue — see below).

## Budget guard

There is no env-enforced spend cap in the worker/engine code. The hard ceiling is the
**OpenRouter key's own budget cap** (`ENGINE_TESTS_OPENROUTER_API_KEY`), plus the job
`timeout-minutes` and the fact that the fixture task is trivial (a one-line greeting change). Each
run appends the key's usage/remaining to the job summary.

## Failure reporting

On any failure the workflow opens — or comments on an existing — **`nightly-real-stack failed`**
issue (label `nightly-failure`) on this repo, with a link to the run and the orchestrator+worker log
tail. Deduplicated, so a run of red nights is one issue thread, not N. A deliberately broken staging
deploy or a revoked sandbox credential therefore surfaces as a diagnosable issue, not a silent skip.

## Calibration / burn-in

Acceptance is **ten consecutive nightly runs with ≤1 model-attributable failure**. Grep the run
history for the machine-readable line:

```
LOOP_RESULT fixture=gradle outcome=pass|fail retried=false stage=<stage>
```

Infra-attributable failures (auth, deploy, GitHub API) are **fixed, not retried away**. If the
*model* leg flakes above ~1/10, make the fixture task more trivial rather than adding a retry — the
nightly deliberately does **not** auto-retry, so the measured rate is the real rate.

## M3 hook

When the leader engine lands, this is where `FLOW_WORKER_ENGINE=leader` runs unattended first: add it
to the loop step's `env:` (one line).
