# M2 follow-ups

Sharp edges deliberately accepted in M2 (auto mode), written down so M3 planning
starts from a list rather than rediscovery. None is a bug; each is a conscious
scope cut from the M2 definition's non-goals.

| # | Edge | Today's behavior | Follow-up |
|---|------|------------------|-----------|
| 1 | **FAILED holds the repo mutex** | A failed pipeline stops the whole repo until a human clears it in the web app — maximum caution. | Relax to per-issue quarantine: fail one issue without stopping the repo, once we trust the failure classification. |
| 2 | **No inbound label-clear sync** | Removing `flow:failed` by hand on GitHub does nothing; clearing is web-app-only. | Watch inbound label edits so removing `flow:failed` (or `ready`) on GitHub is honored as a clear/opt-out. |
| 3 | **Issue comments aren't part of the task** | Only the issue title + body become the task; later comments are ignored. | Fold issue comments (or a designated "task" comment) into the task description. |
| 4 | **No cross-repo graphs** | `blocked by` is resolved within one repo; a cross-repo blocker is ignored. | Resolve dependencies across repos, and mirror/paginate large graphs. |
| 5 | **Labels are a one-way projection (no anti-drift repair)** | Flow writes `flow:*` labels but doesn't reconcile a human deleting/renaming them beyond the next transition's write. | Periodic label-repair pass that re-asserts the projection against drift. |
| 6 | **No review-feedback loop / retry** | A failed session or a PR with review comments just stops; no AI-driven recovery, no retry, no addressing review feedback. | Retry policy + a loop that reads PR review comments and revises the PR. |
| 7 | **One in-flight issue per repo; single local worker** | Repo mutex + a single, locally-run M1 worker. No hosted worker fleet, no parallelism within a repo. | Hosted worker fleet; controlled parallelism across issues once the mutex relaxes (see #1). |
| 8 | **Oldest-first picking only** | Among ready, unblocked candidates, the oldest is picked; no priority. | Configurable priority ordering; configurable label names (`ready`, `flow:*`). |

## Surfaced during the M2 end-to-end demo (story 13)

| # | Edge | Today's behavior | Follow-up |
|---|------|------------------|-----------|
| 9 | **Scheduler can't discover a fresh repo** | `Reconciler.relevantRepos()` (the no-arg/scheduler path) = repos with a live pipeline ∪ pending outbox. Nothing enumerates repos that merely have `ready` issues, so the *initial* pick in a brand-new repo only happens via the webhook's repo-scoped `Reconcile(repo)`. "Scheduler alone would also get there" isn't true for first discovery. | Have the scheduler also scan a configured/installed-app repo set for `ready` candidates, so discovery doesn't depend on the webhook. |
| 10 | **Clearing a pipeline doesn't cancel its session** | `ClearIssuePipeline` marks the pipeline cleared but leaves its session. Harmless when the session is already terminal, but a still-`PENDING`/`RUNNING` session could later publish a second PR for the same issue after a re-pick. | On clear, cancel/fail the linked non-terminal session (and/or have the worker no-op a session whose pipeline is cleared). |
| 11 | **Workspace sync drops executable bits** | The engine's workspace→clone copy loses the `+x` bit (observed: `gradlew` 100755→100644 in the published PR), which can break CI that runs `./gradlew`. | Preserve file modes when materializing the workspace over the clone. |
| 12 | **Exact-match token audience is slash-brittle** | `WORKER_TOKEN_AUDIENCE` is compared to the token `aud` exactly, so a trailing-slash difference 401s. Fine when everything derives `aud` from the same `API_URL`, but a footgun. | Normalize a trailing slash on both sides of the `aud` comparison in `GoogleIdTokenAuthDecorator`. |

When M3 starts, open a GitHub issue per row that's in scope and link it back
here.
