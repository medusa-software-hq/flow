# Flow worker

`flow work` is the component that turns a queued session into a pull
request: it polls the control plane, claims the oldest pending session, runs
the engine against a fresh clone of the target repo, reports progress back as
it goes, and publishes a successful run as a branch + PR.

It's a subcommand of the same `flow` CLI as `complete-task`/`scout-fully`
(see [engine/harness](../engine/harness) and [cli](../cli)), so building or
installing the CLI is all that's needed — there's no separate worker binary.

## Prerequisites

| What | Where it comes from |
|---|---|
| Credentials for `flow-worker` | Be a member of the `flow-admins@medusa.software` Google Group (grants `roles/iam.serviceAccountTokenCreator` on `flow-worker` — see [backend/api/infra/gcp-worker-sa.tf](../backend/api/infra/gcp-worker-sa.tf)), then run `worker/scripts/get-worker-credentials.sh` once to mint short-lived Application Default Credentials via impersonation. This is the only supported path — there's no downloaded-key-file option. ADC is a single global file on the machine (`~/.config/gcloud/application_default_credentials.json`) — running `terraform` or any other `gcloud auth application-default login` afterward overwrites it, silently un-impersonating the worker. Re-run the script if `flow work` starts getting `UNAUTHENTICATED`. |
| A GitHub token with push + PR-create access on the target repo(s) | A classic PAT, fine-grained PAT, or GitHub App installation token — whatever your target repos accept. Used for `git clone`/`push` (via `GIT_ASKPASS`, never on the command line) and the `POST .../pulls` REST call. |
| An OpenRouter API key | Same key the engine already uses for `complete-task`/`scout-fully`. |
| `git` on `PATH` | The worker shells out to it directly (`medusa.commons:git` has no clone/push support). |

## Configuration

All via environment variables, no config file in M1:

| Variable | Purpose |
|---|---|
| `FLOW_API_URL` | Control-plane base URL, e.g. `https://api.flow.example.com` (or `http://localhost:8081` against a local backend). Also the audience the ID token is minted for — must match the control plane's own `WORKER_TOKEN_AUDIENCE`. |
| `FLOW_WORKER_GITHUB_TOKEN` | The GitHub token from the prerequisites step above. |
| `OPENROUTER_API_KEY` | As for every other `flow` subcommand. |

Missing or blank required variables fail fast at startup with a message
naming the specific variable — nothing silently runs half-configured.

Model wiring (which OpenRouter models back the frontline/expert/interpreter
roles) is hardcoded in `cli/src/main/kotlin/software/medusa/flow/cli/main.kt`,
same as every other subcommand; making that configurable is out of scope for
M1.

## Running it

```bash
./worker/scripts/get-worker-credentials.sh  # once per ADC expiry

export FLOW_API_URL=https://api.flow.example.com
export FLOW_WORKER_GITHUB_TOKEN=ghp_...
export OPENROUTER_API_KEY=sk-or-...

flow work
```

It runs until stopped (`Ctrl-C`/`SIGTERM`): claim → process one session to a
terminal state → poll again, sleeping between empty polls. Strictly one
session at a time. Stop it cleanly with `SIGTERM` (a JVM shutdown hook
cancels the in-flight session's coroutine and waits for it to unwind);
`SIGINT` also works in most environments, though in some sandboxes a
signal handler installed by the Armeria/Netty stack can intercept it before
the JVM's own shutdown-hook machinery runs — if `Ctrl-C` doesn't exit
cleanly, `SIGTERM` will.

If the worker process dies mid-session (crash, OOM-kill, `kill -9`), there's
no special recovery: the session simply sits `RUNNING` until its heartbeat
goes stale, at which point the control plane lazily marks it `FAILED`
("Worker lost") the next time anyone reads it (no separate sweep process
needed). Restarting the worker just resumes polling — it keeps no state
between sessions.

## Target-repo preconditions

For a session against a given repo to have a chance of succeeding, that
repo needs:

- A `project.yaml` the engine's manifest loader understands (see
  [engine/universal-project](../engine/universal-project)).
- A green baseline: bootstrap/analyze/test all pass on the default branch
  *before* the engine touches anything — a broken baseline fails the session
  immediately at the initial health gate, before any AI involvement.
- `FLOW_WORKER_GITHUB_TOKEN` needs push access to it, since the worker
  branches (`flow/session-<id>`), commits, and pushes directly.

## Container image (M4 Path B)

[`worker/Dockerfile`](Dockerfile) packages the worker as a hosted, credential-free
container run under [ms-workload](../../workload-home). It bundles a JRE, git, Node
(npm + corepack yarn — the worker locates both at startup), and the pinned `claude`
CLI; the worker runs as PID-1-behind-tini so `SIGTERM` reaches its shutdown hook and
tool subprocesses are reaped. CI builds + pushes it to Artifact Registry
(`publish-cli.yml`, job *Publish worker image*) and prints the digest to pin.

**Nothing secret is baked in.** The configuration surface at runtime:

| Source | Carries | How |
|---|---|---|
| **Baked (image)** | JRE, git, Node/npm/yarn, `claude` CLI, `flow-cli.jar` | in the image; no secrets |
| **Host (mounted)** | the ms-workload **worker identity** (`workerId`/`secret`, broker URL) | `config.json` under `XDG_CONFIG_HOME`, provisioned once at VM create; **reused on restart, never re-registered** |
| **Profile (spawn)** | `FLOW_API_URL`, `FLOW_WORKER_ENGINES` + engine knobs (env); `FLOW_WORKER_GITHUB_TOKEN`, `OPENROUTER_API_KEY`, `ANTHROPIC_API_KEY` (secret refs, resolved worker-side) | ms-workload profile env/secretEnv |
| **Beacon (spawn)** | the worker's **GCP identity** — an audience-bound `flow-worker` ID token for the Flow API | GCE-shaped metadata server under `workload run`; the worker's existing ADC path uses it unmodified (see [design/05-workload-notes.md](../../plan/m4/design/05-workload-notes.md)) |

The image is environment-agnostic: the same digest runs any environment, since every
knob above arrives at spawn. Deploying a new worker version = build a new image +
append an ms-workload profile revision pinning its digest.

## Known M1 limitations

- No PR templates, draft PRs, or review-request automation.
- No cleanup of a stray branch if the worker crashes mid-publish (after
  pushing but before completing the session).
- No retry or cancel for a claimed session — it runs to a terminal state or
  the worker dies and it's lazily expired.
- Polling-only; no push notification when a session is queued.
- Models are hardcoded, not configurable.
