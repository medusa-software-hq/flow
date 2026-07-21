# `system-tests` — typed E2E suite against deployed staging (M5)

Kotlin/JUnit 5 tests that exercise the **deployed** staging environment through
the generated proto clients — the successor to the retired `smoke/smoke.sh` and
`nightly/real-stack.sh`. It runs as the promotion gate (between the staging and
prod deploys) and ad hoc from a developer machine. See [`../docs/testing.md`](../docs/testing.md)
for how it fits the five-layer test map.

This module is **excluded from the regular `check`/PR build** — it needs a live
staging deploy and credentials, and (for the loop tier) real model credits. The
`test` task compiles nothing here; everything lives in the `systemTest` source
set, run by the `systemTest` task.

## Tiers

Tests are tagged (see [`Tiers.kt`](src/systemTest/kotlin/software/medusa/flow/systemtest/Tiers.kt)):

- **`@Smoke`** — fast, stateless post-deploy checks; **no live worker required**
  (a worker outage must never block promoting an API-only fix). *Story 02.*
- **`@Loop`** — the full real cycle through staging's admin-run worker; needs the
  standing "a worker is alive" assumption and model credits. Guarded by the
  worker-liveness preflight, so a worker outage reads as a distinct **"staging
  worker down"** rather than a generic timeout. *Story 03.*

## Running it

```bash
# Everything (both tiers):
API_URL=https://api.flow-baseline-staging.medusa.software \
WORKER_SA_EMAIL=flow-worker@<staging-project>.iam.gserviceaccount.com \
  ./gradlew :system-tests:systemTest

# One tier (the gate runs these as separate steps):
./gradlew :system-tests:systemTest -PsystemTestTags=smoke
./gradlew :system-tests:systemTest -PsystemTestTags=loop
```

Config (env, mirroring `smoke.sh`):

| Var | Meaning | Default |
|---|---|---|
| `API_URL` | Deployed API base URL; also the ID-token audience. **Required** — unset ⇒ tests *skip* (not fail). | — |
| `WEB_URL` | Deployed SPA URL. | `API_URL` with the `api.` prefix stripped |
| `WORKER_SA_EMAIL` | Worker SA to impersonate for the API identity. Set ⇒ mint a worker-audience token by impersonating it (the gate path). Unset ⇒ use ADC directly (a laptop whose ADC is already authorized). | — |

Auth mirrors the worker (`WrkGrpcApiClient`): a Google ID token (audience =
`API_URL`) from Application Default Credentials, attached to every gRPC call. On
a laptop, authenticate ADC first (`gcloud auth application-default login`, with
permission to impersonate the worker SA); in CI the WIF step provides it.

## Worker liveness

The loop tier's preflight
([`WorkerLiveness`](src/systemTest/kotlin/software/medusa/flow/systemtest/WorkerLiveness.kt))
reads the control plane's worker fleet registry (`WorkerService.ListWorkers`) and
requires a worker registered within the last 90s. The staging worker
re-registers every ~15s; a stopped worker is caught within a couple of minutes as
a distinct **"staging worker down"** failure. It also prints the version/digest it
tested against (version-skew surfacing lands in story 04).
