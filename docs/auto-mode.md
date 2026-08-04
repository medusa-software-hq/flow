# Auto mode

In **auto mode**, Flow watches a repository's issues and works them on its own:
it picks a ready issue, runs a session, opens a PR, and — once you merge that PR
and its checks pass — closes the issue and moves on to whatever the issue was
blocking. No one submits a task in the web app; the issue *is* the task.

This is the M2 counterpart to the manual flow (open the web app, write a task,
get a PR). Manual sessions still work exactly as before and are unaffected by
auto mode.

## For issue authors

### Opt an issue in: the `flow:ready` label

Add the **`flow:ready`** label to an issue to make it eligible for autonomous pickup.
The issue's **title + body become the task** — write them the way you'd write a
task for the manual flow: a clear title (it becomes the PR title) and a body
that says what "done" looks like. Everything the engine needs to verify itself
must come from the repo's own `project.yaml` build/test tooling, exactly as in
manual mode.

An issue with no `flow:ready` label is invisible to auto mode.

### Order work with native "blocked by"

Flow respects GitHub's **native issue dependencies**. If issue B is *blocked by*
A (Issue → **Relationships** → *Blocked by*), Flow will not pick B until A is
**closed**. A chain A ← B ← C is worked strictly in order: A, then B once A
closes, then C once B closes — you can label all three `flow:ready` up front and
leave them.

"Unblocked" means *every* blocker is closed. A blocker that's still open (even
if merged elsewhere) keeps its dependents waiting.

### One issue per repo at a time

A repository runs **one pipeline at a time**. While Flow is working an issue
(through PR review and merge checks), no other issue in that repo is picked up —
this is deliberate, so a repo never has two auto-PRs racing each other. Other
repos are independent.

### What the `flow:*` labels mean

Flow projects each pipeline's state onto the issue as a label (the database is
authoritative; the labels are a synced view):

| Label | Meaning |
|---|---|
| `flow:in-progress` | A session is running against this issue; no PR yet. |
| `flow:pr-open` | A PR is open (and, after you merge, its merge-commit checks are running). |
| `flow:failed` | The session or its checks failed. **The repo is stopped until this is cleared** (see below). |

A closed issue with none of these labels was completed normally. Don't hand-edit
`flow:*` labels — Flow owns them and will re-sync them; to intervene, use the
web app (below).

### The PR Flow opens

For an issue-linked run the PR is branch `flow/issue-<n>`, titled from the issue,
with the task in the body and a **`Refs #<n>`** line — a reference, *not* a
closing keyword. Flow closes the issue itself, only after the PR is merged and
its merge-commit checks pass, with an annotation comment linking the PR and
session. (This is why the body says `Refs`, never `Closes`: merging must not
auto-close the issue out from under the merge gate.)

### When something fails: clearing in the web app

If a pipeline ends **`flow:failed`**, the repository is intentionally **stopped**
— no further issue in that repo is picked until a human looks. The failure
summary is posted as a comment on the issue and shown in the web app's
**Pipelines** view.

To resume, open **Pipelines**, find the failed row, and click **Clear**. Clearing
releases the repo and makes the issue eligible to be picked again on the next
cycle. This is a deliberate web-app-only control: Flow does not auto-retry, and
it does not watch for you removing the `flow:failed` label by hand.

## For operators

### Two triggers, one backstop

Reconciles — the passes that observe PR/merge state, close done issues, pick the
next issue, and flush the GitHub outbox — are driven by:

- **GitHub webhooks** (`POST /webhook/github`): latency. An issue labeled
  `flow:ready`, a merged PR, or a finished check wakes the reconciler within seconds.
  Purely an accelerant — see [../backend/infra/github-webhook.md](../backend/infra/github-webhook.md)
  for setup. With webhooks off, everything still works, just slower.
- **Cloud Scheduler** (`api-reconcile`, every ~3 min): the correctness backstop.
  A full reconcile of every repo with live pipelines or pending outbox.

The two are redundant by design: the system is correct on the scheduler alone,
and merely faster with webhooks.

### Watching reconciles / stuck outbox

Reconcile passes log structured start/summary lines; the log-based
"no successful reconcile in 15 min" alerting query is documented in
[../backend/infra/reconcile-monitoring.md](../backend/infra/reconcile-monitoring.md).

Every GitHub side effect (label changes, the annotation comment, closing the
issue) rides a per-issue **outbox** that's drained idempotently and retried with
backoff. An entry that keeps failing is **never dropped**: after enough attempts
it's surfaced in the Pipelines view as a **"GitHub sync issue"** badge on the
affected pipeline. That badge means GitHub writes are behind (rate limit, a
transient API failure, a permissions problem) — the pipeline state itself is
still correct in the database.

### Crash convergence

The control plane holds no in-memory work queue: state lives in Postgres and the
outbox. If it restarts mid-cycle, the next reconcile converges — outbox entries
drain, transitions are re-derived from PR/merge state, nothing is lost. The one
accepted edge is a possibly-duplicated annotation *comment* (comments are
annotations, not state).
