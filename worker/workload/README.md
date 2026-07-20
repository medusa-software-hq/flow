# ms-workload profiles for the hosted Flow worker (M4 Path B / B5)

One [ms-workload](../../../workload-home) profile per (environment × worker flavor).
The **spec** is checked in here; the profile itself is created/updated with the
upstream admin CLI (`workload admin profiles create -f <spec>.json`), which mints
an immutable revision. "Deploy a new worker version" = re-run `create`/`update`
with a new `dockerImageDigest` — the running VM picks it up on restart.

- [`flow-worker-staging.profile.json`](flow-worker-staging.profile.json) — the
  baseline staging worker. (A prod profile is the stretch story B7.)

## What the profile carries

| Kind | Key | Value |
|---|---|---|
| target SA | `targetServiceAccount` | `flow-worker@ms-flow-d14f8295` — the broker impersonates it (opt-in in root `infra/`) and mints its ID token via Beacon; it's on the Flow API's `WORKER_SA_EMAILS` allowlist |
| env | `FLOW_API_URL` | staging API |
| env | `FLOW_WORKER_ENGINES` | `builtin` (the nightly runs builtin; add `claude` once Path A's worker wiring lands + `CLAUDE_CODE_OAUTH_TOKEN` is set) |
| env | `FLOW_WORKER_GITHUB_APP_CLIENT_ID` | the `medusa-flow-nightly` App — the worker mints its own installation token from it |
| secret | `FLOW_WORKER_GITHUB_APP_PEM` | the App's **PKCS#8** private key (`openssl pkcs8 -topk8 -nocrypt`) — resolved worker-side from Secret Manager |
| secret | `OPENROUTER_API_KEY` | builtin-engine model key |
| secret | `CLAUDE_CODE_OAUTH_TOKEN` | claude-engine auth (present for when `claude` is enabled) |
| image | `dockerImage` / `dockerImageDigest` | the B4 worker image, **pinned by digest** |

There is **no GitHub token and no `ANTHROPIC_API_KEY`** — the worker mints its App
token itself, and Claude auth is the OAuth token, by decision.

## Placeholders to fill before `profiles create`

- `<STAGING_AR_ENDPOINT>` — the staging Artifact Registry endpoint (`vars.GCP_AR_REPO_ENDPOINT`, e.g. `europe-docker.pkg.dev/ms-flow-d14f8295/flow`).
- `<WORKER_IMAGE_DIGEST>` — the current worker image digest, printed by the latest **Publish CLI → Publish worker image** run (job summary). Deliberately not committed: it changes on every worker/engine merge, and the profile pins *a specific* build.

The secrets, the broker impersonation grant, and the Artifact Registry read grant
are provisioned by root `infra/` (see `infra/gcp-workload-worker.tf`); their values
are set by hand after apply. `VerifyProfile` must be green (binding + secret access
+ image digest all resolve) before the profile is claimable.
