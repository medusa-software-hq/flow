# ms-workload profiles for the hosted Flow worker (M4 Path B / B5)

One [ms-workload](../../../workload-home) profile per (environment × worker flavor).
The **spec** is checked in here; the profile itself is created/updated with the
upstream admin CLI (`workload admin profiles create -f <spec>.json`), which mints
an immutable revision. "Deploy a new worker version" = re-run `create`/`update`
with a new `dockerImageDigest` — the running VM picks it up on restart.

- [`flow-worker-staging.profile.json`](flow-worker-staging.profile.json) — the
  baseline staging worker (sandbox org `medusa-software-test-hq`, its own
  `medusa-flow-nightly` App).
- [`flow-worker-prod.profile.json`](flow-worker-prod.profile.json) — the baseline
  prod worker (B7). Prod org `medusa-software-hq`; reuses the control-plane
  `medusa-flow` App's PEM (`api-github-app-pem`), so no separate worker-App secret.

## What the profile carries

| Kind | Key | Value |
|---|---|---|
| target SA | `targetServiceAccount` | the per-env `flow-worker@<project>` — the broker impersonates it (opt-in in root `infra/`) and mints its ID token via Beacon; it's on the Flow API's `WORKER_SA_EMAILS` allowlist |
| env | `FLOW_API_URL` | the env's Flow API |
| env | `FLOW_WORKER_GITHUB_APP_CLIENT_ID` | the env's App — the worker mints its own installation token from it |
| env | `FLOW_CLAUDE_MAX_BUDGET_USD` | per-session Claude cost cap (runaway-guard; app default `5`) |
| env | `FLOW_AUTHOR_EMAIL` | commit author email (non-secret config) |
| env | `FLOW_ENABLE_GPG_SIGNING` | `true`/`false` — off by default; when on, the worker re-signs commits with the GPG key |
| secret | `FLOW_WORKER_GITHUB_APP_PEM` | the App's **PKCS#8** private key (`openssl pkcs8 -topk8 -nocrypt`) — resolved worker-side from Secret Manager |
| secret | `OPENROUTER_API_KEY` | builtin-engine model key |
| secret | `CLAUDE_CODE_OAUTH_TOKEN` | claude-engine auth (workers are uniform — every worker runs every engine) |
| secret | `FLOW_WORKER_GPG_PRIVATE_KEY` | passphrase-less commit-signing key. **Only wired into `secretEnvVars` once a real key exists** (the container is created empty); referencing an empty secret fails resolution |
| image | `dockerImage` / `dockerImageDigest` | the B4 worker image, **pinned by digest** |

There is **no GitHub token and no `ANTHROPIC_API_KEY`** — the worker mints its App
token itself, and Claude auth is the OAuth token, by decision. `FLOW_AUTHOR_EMAIL`
and `FLOW_ENABLE_GPG_SIGNING` are **non-secret config** and live here in `envVars`,
not in Secret Manager; only the GPG *key* is a secret.

## Placeholders to fill before `profiles create`

- `<STAGING_AR_ENDPOINT>` / `<PROD_AR_ENDPOINT>` — the env's Artifact Registry endpoint (`vars.GCP_AR_REPO_ENDPOINT`, e.g. `europe-west1-docker.pkg.dev/ms-flow-b71f4835/flow` for prod).
- `<WORKER_IMAGE_DIGEST>` — the current worker image digest, printed by the latest **Publish CLI → Publish worker image** run (job summary). Deliberately not committed: it changes on every worker/engine merge, and the profile pins *a specific* build.

The secrets, the broker impersonation grant, and the Artifact Registry read grant
are provisioned by root `infra/` (see `infra/gcp-workload-worker.tf`); their values
are set by hand after apply. `VerifyProfile` must be green (binding + secret access
+ image digest all resolve) before the profile is claimable.
