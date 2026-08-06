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
| Credentials for `flow-worker` | Be a member of the `flow-admins@medusa.software` Google Group (grants `roles/iam.serviceAccountTokenCreator` on `flow-worker` — see [backend/infra/gcp-worker-sa.tf](../backend/infra/gcp-worker-sa.tf)), then run `worker/scripts/get-worker-credentials.sh` once to mint short-lived Application Default Credentials via impersonation. This is the only supported path — there's no downloaded-key-file option. ADC is a single global file on the machine (`~/.config/gcloud/application_default_credentials.json`) — running `terraform` or any other `gcloud auth application-default login` afterward overwrites it, silently un-impersonating the worker. Re-run the script if `flow work` starts getting `UNAUTHENTICATED`. |
| A GitHub App with Contents + Pull-requests write, installed on the target repo(s) | The worker mints + refreshes its **own** installation token from the App's client ID + private key — no static token or PAT. Used for `git clone`/`push` (via `GIT_ASKPASS`, never on the command line) and the `POST .../pulls` REST call. The PEM must be **PKCS#8** (`openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pk8.pem`). |
| An OpenRouter API key | Same key the engine already uses for `complete-task`/`scout-fully`. |
| `git` on `PATH` | The worker shells out to it directly (`medusa.commons:git` has no clone/push support). |

## Configuration

All via environment variables, no config file in M1:

| Variable | Purpose |
|---|---|
| `FLOW_API_URL` | Control-plane base URL, e.g. `https://api.flow.example.com` (or `http://localhost:8081` against a local backend). Also the audience the ID token is minted for — must match the control plane's own `WORKER_TOKEN_AUDIENCE`. |
| `FLOW_WORKER_GITHUB_APP_CLIENT_ID` | The GitHub App's client ID (the App from the prerequisites step). |
| `FLOW_WORKER_GITHUB_APP_PEM` | The App's PKCS#8 private key. The worker signs an App JWT with it and mints a per-repo installation token, refreshing it as it nears expiry. |
| `OPENROUTER_API_KEY` | As for every other `flow` subcommand. |
| `FLOW_WORKER_DRAIN_DEADLINE_MILLIS` | Optional. Bounds how long a `SIGTERM`-triggered drain waits for the in-flight session before force-killing it — see [Running it](#running-it). Unset means no bound. |
| `FLOW_WORKER_ENGINE` | Optional: `leader` (default, M3-12), `builtin`, or `claude`. Which engine this worker runs an unspecified-engine session on — see [`docs/engines.md`](../docs/engines.md#flow_worker_engine--pointing-a-worker-at-one-engine-m3-09m3-11m3-12). `builtin` and `claude` remain fully supported as fallback switches. |

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
export FLOW_WORKER_GITHUB_APP_CLIENT_ID=Iv1...
export FLOW_WORKER_GITHUB_APP_PEM="$(cat app.pk8.pem)"
export OPENROUTER_API_KEY=sk-or-...

flow work
```

It runs until stopped (`Ctrl-C`/`SIGTERM`): claim → process one session to a
terminal state → poll again, sleeping between empty polls. Strictly one
session at a time.

`SIGTERM`/`Ctrl-C` mean **drain**, not abort: a JVM shutdown hook cordons the
worker (no new sessions are claimed) and lets whatever session is already in
flight run to completion, then the process exits `0`. An idle worker has
nothing to drain, so it exits immediately. This matches the Docker-native
`docker stop -t <D>` contract, so the same signal a container supervisor sends
on a routine roll is safe to send to a worker mid-session — no in-flight work
is abandoned.

Set `FLOW_WORKER_DRAIN_DEADLINE_MILLIS` to bound how long the worker will
drain for — mirror it to the supervisor's `docker stop -t <D>` timeout (in
milliseconds, and a little under `D` to leave room to act before the external
`SIGKILL` lands). Past that deadline the worker force-kills the in-flight
session itself and reports it with an explicit reason
(`worker_replaced_at_drain_deadline`), flagged as a worker death, before
exiting, so the affected issue is identifiable at a glance rather than left to
expire lazily via heartbeat timeout. Unset (the default), draining has no
self-imposed bound — it waits for the session to finish however long that
takes.

In some sandboxes a signal handler installed by the Armeria/Netty stack can
intercept `SIGINT` before the JVM's own shutdown-hook machinery runs — if
`Ctrl-C` doesn't exit cleanly, `SIGTERM` will.

If the worker process dies mid-session (crash, OOM-kill, `kill -9`), the
session simply sits `RUNNING` until its heartbeat goes stale, at which point
the control plane lazily notices it the next time anyone reads it (no
separate sweep process needed). Worker death — whether a crash caught this
way or the drain-deadline force-kill above — is a retry, not an abandonment:
the session is requeued to `PENDING` for a fresh attempt (re-run from the
issue; no attempt is made to resume or migrate its in-flight state) up to a
bounded number of times ([`SessionStore.maxWorkerDeathRetries`][sessionstore]
retries) before it is finally marked `FAILED` ("Worker lost"). A genuine
engine/task failure — the worker is alive and reports the failure itself —
always terminates the session immediately; only worker death requeues.
Restarting the worker just resumes polling — it keeps no state between
sessions, and a requeued session run by a new worker starts over from
scratch.

[sessionstore]: ../backend/api/shared/src/main/kotlin/software/medusa/flow/server/SessionStore.kt

## Target-repo preconditions

For a session against a given repo to have a chance of succeeding, that
repo needs:

- A `project.yaml` the engine's manifest loader understands (see
  [engine/universal-project](../engine/universal-project)).
- A green baseline: bootstrap/analyze/test all pass on the default branch
  *before* the engine touches anything — a broken baseline fails the session
  immediately at the initial health gate, before any AI involvement.
- The GitHub App (`FLOW_WORKER_GITHUB_APP_*`) must be installed on it with push
  access, since the worker branches (`flow/session-<id>`), commits, and pushes
  directly.

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
| **Baked (image)** | JRE, git, Node/npm/yarn, `claude` CLI, `flow-cli.jar`, `FLOW_WORKER_VERSION` (the build's git sha) | in the image; no secrets |
| **Host (mounted)** | the ms-workload **worker identity** (`workerId`/`secret`, broker URL) | `config.json` under `XDG_CONFIG_HOME`, provisioned once at VM create; **reused on restart, never re-registered** |
| **Profile (spawn)** | `FLOW_API_URL`, `FLOW_WORKER_ENGINES`, `FLOW_WORKER_GITHUB_APP_CLIENT_ID`, `FLOW_WORKER_IMAGE_DIGEST` (env); `FLOW_WORKER_GITHUB_APP_PEM`, `OPENROUTER_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN` (secret refs, resolved worker-side) | ms-workload profile env/secretEnv |
| **Beacon (spawn)** | the worker's **GCP identity** — an audience-bound `flow-worker` ID token for the Flow API | GCE-shaped metadata server under `workload run`; the worker's existing ADC path uses it unmodified (see [design/05-workload-notes.md](../../plan/m4/design/05-workload-notes.md)) |

The image is environment-agnostic: the same digest runs any environment, since every
knob above arrives at spawn. Deploying a new worker version = build a new image +
append an ms-workload profile revision pinning its digest.

## Version reporting & skew (M5)

The worker registers itself with the control-plane **fleet registry** every ~15s
(`WrkRegistrationLoop` → `WorkerService.RegisterWorker`), reporting a stable
`worker_id` plus the build it runs. The M5 system-test gate reads this
(`WorkerService.ListWorkers`) for two things: a distinct **"staging worker down"**
when nothing has registered recently, and the **version it tested against**, so
skew is a printed fact rather than a silent gap.

Where the version/digest come from:

| Field | Source | Notes |
|---|---|---|
| `FLOW_WORKER_VERSION` | **baked at image build** (`worker/Dockerfile` `ARG`, CI passes `github.sha`) | equals the image tag `worker:<sha>`; a local `docker build` leaves it `unknown`. Overridable by profile env. |
| `FLOW_WORKER_IMAGE_DIGEST` | **profile (spawn)** — the ms-workload profile that pins the digest injects it | the running container can't compute its own manifest digest; the profile knows the digest it pinned. Optional; version is the primary skew signal. |
| `FLOW_WORKER_ID` | profile env, else host name, else a random per-process id | pin it for a stable identity across restarts. |

**The known hole — version skew.** The admin-run worker executes whatever image it
was last restarted into, so a merge that breaks *worker-side* code passes the gate
until the worker is bumped. M5 makes this **diagnosable, not impossible**: the gate
prints the worker version and, when it knows the build under promotion
(`EXPECTED_WORKER_VERSION`), warns loudly on a mismatch — but does **not** fail the
gate (advisory). A red gate run right after a worker-touching merge is then
immediately attributable.

**Bumping the worker (admin).** After a merge that touches worker-side code
(`worker/**`, `engine/**`, `cli/**`), the running worker is stale until bumped:

1. `Publish CLI` (post-merge) builds and pushes a new `worker:<sha>` image and
   prints its digest in the job summary.
2. Append an ms-workload profile revision pinning that digest (and, ideally, set
   `FLOW_WORKER_IMAGE_DIGEST` to it in the profile env).
3. Restart the worker onto the new revision: `SIGTERM` the running worker (it
   drains — see [Running it](#running-it) — rather than abandoning whatever
   session is in flight) and re-run
   `ms-workload worker run --profile flow-worker-staging`, which starts a fresh
   process onto the new digest. There's no supervisor that does this
   automatically today; it's a manual step.

Until then the next gate run warns about skew. **Treat a worker-touching merge as
warranting a bump before trusting the next gate.**

**Auto-reload is out of scope for M5** — detecting a new profile revision and
restarting onto it automatically belongs to the Workload project's host design
(the persistent-VM / supervisor debate). The worker's half of the contract — that
a restart signal drains rather than abandons the in-flight session — is real
(see [Running it](#running-it)); what's still missing is the automation that
would drive it on every profile-revision bump without an admin doing it by hand.
M5 only consumes the outcome and, in the meantime, makes the skew loud. This
section is Flow's requirement into that debate: the fix is auto-reload on
profile-revision bump, built on top of the drain contract that already exists.

## Known M1 limitations

- No PR templates, draft PRs, or review-request automation.
- No cleanup of a stray branch if the worker crashes mid-publish (after
  pushing but before completing the session).
- No cancel for a claimed session — it runs to a terminal state, or the
  worker dies and it's lazily expired (which now requeues it for a bounded
  number of retries — see [Running it](#running-it) — rather than abandoning
  it, but there's still no way to cancel a session on demand).
- Polling-only; no push notification when a session is queued.
- Models are hardcoded, not configurable.
