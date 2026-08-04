# Flow

Flow is an AI coding agent: give it a git repository and a task written in
plain Markdown, and it explores the codebase, plans a change, implements it,
and verifies the result against the project's own build/lint/test tooling —
retrying until the project is healthy or it runs out of attempts. The
deliverable is a pull request, not a chat transcript.

It ships as three things: an **engine** that does the actual work, a **CLI**
that runs the engine locally, and a hosted **web app + worker** that let
someone submit a task from a browser and get a PR back without touching a
terminal.

Flow can run a session with one of two engines — the **Builtin** engine
described below, or the **Claude Agent** engine (Claude Code driven as a
subprocess), which also works on repositories without a Flow manifest. You pick
per session. See [`docs/engines.md`](docs/engines.md).

## How it works

The engine keeps two views of the repository side by side:

- a **virtual editor** — an in-memory, progressively-revealed model of the
  worktree (directories start collapsed, files start closed; the model opens
  what it needs), which is what actually gets rendered into prompts;
- a **physical workspace** — a real, throwaway on-disk copy where build/test
  tools actually run.

Work is split across three LLM roles, deliberately wired to different models:

| Role | Job |
|---|---|
| **Frontline** | High-context, cheap model. Reads the (revealed) worktree and reasons about it in free-form prose — never structured output. |
| **Expert** | Strong, low-context model. Never sees the workspace directly, only the task and the frontline's brief; produces the implementation plan. |
| **Interpreter** | Cheap, structured-output model. Distils the frontline's prose into machine-actionable structure (what to reveal next, what patch to apply). Trusted for content, not format — it's doing extraction the frontline can't reliably do itself. |

A cheap high-context model does the reading, a smart low-context model does
the thinking, and a cheap structured pass bridges prose into actions.

The pipeline, per task: load the project's manifest → allocate a physical
workspace → run an initial health gate (bootstrap/analyze/test — a broken
baseline fails fast, before any model is involved) → **scout** the codebase
(frontline proposes what to look at, interpreter turns that into virtual-editor
reveals, repeat until the frontline says it's seen enough) → the frontline
writes a brief for the expert → the expert plans the change → an
**implementation loop** (frontline proposes a patch, interpreter turns it into
concrete file edits, edits are applied to the physical workspace, re-run
analyze/test, fold failures back into the conversation and retry) up to a
fixed attempt budget.

## Repository map

| Path | What it is |
|---|---|
| [`engine/`](engine) | The harness and its subsystems — `harness` (the pipeline above), `virtual-editor`, `universal-project` (toolchain-agnostic bootstrap/analyze/test/normalize), `physical-workspace`, `toolchains/` (Gradle, Node.js) |
| [`cli/`](cli) | The `flow` CLI — `scout-fully` (just the scouting phase), `complete-task` (the full pipeline against a local `--workdir`), `work` (see below) |
| [`worker/`](worker) | `flow work`: polls a control-plane API, claims a queued session, runs the engine against a fresh clone, and publishes a successful run as a GitHub PR. See [worker/README.md](worker/README.md). |
| [`backend/api/`](backend/api) | The control-plane API (Armeria, gRPC + gRPC-Web) backing the web app and the worker: session queueing (Postgres via SQLDelight/Flyway), GitHub repo listing, auth |
| [`web-app/`](web-app) | The web app (React + Mantine) — submit a task against a GitHub repo, watch progress, get a PR link |
| [`proto/`](proto) | Protobuf service/message definitions shared by the backend, web app, and worker |
| `backend/infra/`, `web-app/infra/`, `infra/` | Terraform (GCP Cloud Run, Neon Postgres, Cloudflare DNS) |
| `config/`, `gradle/`, `Taskfile.yml` | Formatting (ktfmt), static analysis (detekt), the Gradle version catalog, and the cross-language task runner |

## End-to-end flow

A user opens the web app, picks a GitHub repository and writes a task in
Markdown, and submits it. That creates a *session* (`PENDING`) on the control
plane. A running `flow work` process polls the control plane, claims the
session (`RUNNING`), clones the repo, runs the engine pipeline above while
reporting coarse progress back as events, and — if the engine succeeds —
branches, commits, pushes, and opens a PR, marking the session `COMPLETED`
with the PR link. If the engine can't get the project healthy, the session
ends `FAILED` with a human-readable summary; there's no partial/resumable
state, a failed or crashed run is simply retried as a new session.

## Auto mode

Beyond the manual flow above, Flow can watch a repository's **issues** and work
them on its own: label an issue `flow:ready`, and Flow picks it, runs a session,
opens a PR, and — once you merge it — closes the issue and moves to whatever it
unblocked. One issue per repo at a time; native GitHub "blocked by" orders the
work; failures stop the repo until a human clears them in the web app.

- Issue authors and operators: [docs/auto-mode.md](docs/auto-mode.md)
- Acceptance-demo runbook: [docs/m2-demo-runbook.md](docs/m2-demo-runbook.md)
- Known edges deferred past M2: [docs/m2-follow-ups.md](docs/m2-follow-ups.md)

## Hosting

The web app and control-plane API run on managed cloud infrastructure, but the
**workers run on on-premises hardware** — long-lived machines that poll the API,
claim sessions, and run the engine. Each worker is a container managed via
**[Workload](https://workload-baseline.medusa.software/)**: the running image is
pinned by digest in a per-environment profile (`flow-worker-staging`,
`flow-worker-prod`), with identity, environment, and secrets injected at spawn,
so the same env-agnostic image serves every environment.

Unlike the API — which promotes to prod automatically on merge — a worker is
rolled by hand and deliberately: CI (`Publish CLI`) builds the worker image and
prints its digest, then an operator pins that digest in a new Workload profile
revision and restarts the worker. Because the two move on different cadences they
can briefly skew; the smoke/loop gates report the running worker's version
against the deployed API so any skew is visible. Mechanics are in
[worker/README.md](worker/README.md).

## Building

Kotlin/JVM throughout (Java 21 toolchain), Gradle with a version catalog at
`gradle/libs.versions.toml`. `./gradlew build` builds and tests everything;
`./gradlew :cli:installDist` produces a runnable `flow` binary at
`cli/build/install/cli/bin/cli`. The web app is a separate Vite/React project
under `web-app/frontend`.

For running the worker specifically — configuration, credentials, and
target-repo preconditions — see [worker/README.md](worker/README.md).

How the system is verified — the five test layers, which one covers each
acceptance-demo step, and how to run each locally — is in
[docs/testing.md](docs/testing.md).
