# Sandbox org + fixtures

The safe blast radius: a dedicated GitHub org where the **staging** stack and the **nightly**
run (M2.5 story 09) can create and close issues, push branches, and open PRs freely, without
ever touching a production repo.

## The org and the App (one-time, manual)

- **Org:** [`medusa-software-test-hq`](https://github.com/medusa-software-test-hq).
- **App:** `medusa-flow-test` (Client ID `Iv23liGENDkcxvvs8EwJ`), same permissions and events as
  the production App, **installed org-wide** (`repository_selection: all`), so every fixture repo
  is covered automatically. Its private key lives only in staging's Secret Manager
  (`api-github-app-pem` in the staging GCP project).

### Credential boundary (do not cross)

- The **staging App is installed only inside this org** — never on a production repo.
- The **production App is never installed inside this org**.

The App's credential boundary *is* the environment boundary: a token minted for staging can only
act here, and a token minted for prod can never act here. (This is the same principle as the
per-environment `github_target_org`, Google OAuth client, and worker token audience.)

## The fixture repository

[`medusa-software-test-hq/flow-sandbox-fixture`](https://github.com/medusa-software-test-hq/flow-sandbox-fixture)
— a small, dependency-free Gradle/Java project with a green baseline (`Greeter` + a `verifyGreeting`
check), a `project.yaml`, and a `gradle.module.yaml` describing its analyze/test steps. It's the
same shape the hermetic loop test drives, so a real model can complete its tasks with a high pass
rate.

Its `flow:ready` issue chain:

| Issue | State | Task |
|---|---|---|
| "Change the greeting to Goodbye" | ready, unblocked | edit `Greeter.GREETING` + the matching check |
| "Add a farewell to the greeter" | ready, **native-blocked-by** the first | add a `FAREWELL` constant |

The block is a real GitHub issue dependency (the `blockedBy` GraphQL connection the reconciler
reads), so Flow must close the first before the second becomes pickable — exercising the
blocked-by ordering end to end.

## Seeding and resetting

```bash
./sandbox/seed.sh
```

Idempotent: run it to seed from scratch **or** to reset after a nightly run or a manual experiment.
It force-restores the baseline `main`, deletes the `flow/*` branches and closes the PRs a prior loop
left behind, closes leftover issues, and recreates the `flow:ready` chain with its blocked-by edge.
(GitHub never reuses issue numbers, so the chain is recreated rather than reopened; earlier runs'
issues stay closed.)

Requires `gh` authenticated as an **admin of the sandbox org**, plus `git`.

## Notes

- **No merge-CI workflow on the fixture, by design.** The reconciler's merge gate treats a merge
  with no checks as "merge alone suffices" (after a short grace period), so the loop still converges
  — and the fixture avoids a flaky external Actions build. The *green* merge-gate path is already
  covered deterministically by the hermetic loop test (story 03) against the stub. Add a
  `.github/workflows/merge-pr.yml` running `gradle build` if a future story wants the sandbox to
  exercise that path too.
- The fixture content here is the source of truth; `seed.sh` pushes it. Issues can't be
  Terraform-managed by the GitHub provider, which is why seeding is a script rather than
  `.github/config` Terraform.
