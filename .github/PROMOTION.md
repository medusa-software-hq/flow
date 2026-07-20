# Deployment promotion

How code reaches production. Both deployables promote through a single chained run per trunk merge:

```
build → deploy staging → smoke (staging) → deploy prod
```

- **Promote API** (`promote-api.yml`) — path filter `backend/api/impl/**`.
- **Promote web SPA** (`promote-web-spa.yml`) — path filter `apps/web/spa/**`, `proto/**`.

The gate between staging and prod is the staging smoke suite (`check-staging-smoke.yml`, story 08),
wired in as a `workflow_call` job. `deploy-prod` declares `needs: smoke`, so **prod cannot deploy
unless staging smoke is green in the same run**.

## Build-once / promote-by-digest (API)

The API promotes the *same artifact* it validated on staging. Each environment owns an independent
Artifact Registry (deliberately — the same no-cross-environment-coupling principle that keeps the
Neon projects independent), so rather than copy the image across a cross-project grant, the prod job
rebuilds it. jib builds are reproducible (epoch-0 creation time, deterministic layers), so the
prod-side image is bit-identical; the `Assert digest matches the staging-validated artifact` step
compares the prod digest against `deploy-staging`'s output and **fails the promotion closed** if they
diverge. Net guarantee: prod runs exactly what staging's smoke exercised, verified by digest
comparison in the job log — with zero cross-environment IAM.

The **SPA is not build-once**: its bundle bakes environment-specific config (`VITE_API_URL`,
`VITE_GOOGLE_CLIENT_ID`, `VITE_GOOGLE_HD`) at build time, so staging and prod images legitimately
differ. There is no digest to assert; the promotion property for the SPA is ordering + the smoke gate.

## The prod human gate — phase 1 vs. phase 2

The design's **phase 1** puts a human-approval park in front of prod: a `production` GitHub
Environment with a **required reviewer**. That protection rule is **Enterprise-only for private
repositories** on this account's plan (the reviewer requirement was dropped from the `production`
environment for that reason). So today the gate is the **smoke job dependency** alone — prod
promotes automatically once staging smoke passes. That is effectively the design's **phase 2**
(auto-promote), reached early by the platform limitation rather than by choice.

**To restore the phase-1 human gate** (no workflow change needed):
add a required reviewer to the `production` environment in repo Settings → Environments. The
`deploy-prod` jobs already declare `environment: production`, so the run will park for approval at
that job automatically.

**Phase-2 flip criteria** (from the environments-and-CD design doc) — auto-promote is appropriate
once: staging smoke is trusted to catch what a human reviewer would (broad enough coverage, low
flake), and rollback is fast. Both hold here (smoke covers the API request surface + SPA
reachability; rollback is a one-click job re-run, below), which is why running without the reviewer
is acceptable in the interim rather than a regression — the previous matrix deployed prod on every
merge with *no* staging gate at all, so the smoke-gated chain is strictly safer.

## Rollback

To roll back, **re-run the `deploy-prod` job of an earlier green promotion run** (Actions → the run →
Re-run jobs). Because prod deploys by digest (API) or by the immutable per-sha image (SPA), re-running
an older run re-points Cloud Run at that run's exact artifact — no rebuild of the current trunk, no
new merge. Pick the last known-good run and re-run its prod deploy job.

## Infra apply ordering

The `apply-*` workflows (API/web infra, domain mappings) follow the same staging-first ordering:
`max-parallel: 1` with staging listed first and `fail-fast: true`, so a failed staging apply cancels
the queued production apply — broken infra never reaches prod. There is no smoke gate for infra
(smoke exercises the deployed app, not Terraform state).
