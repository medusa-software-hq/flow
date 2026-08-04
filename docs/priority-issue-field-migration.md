# Priority: labels → Issue Fields

How Flow's `flow:ready` priority ordering moves from `priority:{urgent,high,medium,low}` labels to
a native GitHub **Issue Field** (public preview, 2026-03-12 —
https://github.blog/changelog/2026-03-12-issue-fields-structured-issue-metadata-is-in-public-preview/).
Code side: `IssuePriority`, `ReconcilePicker`, `GitHubAppCandidateClient` in
`backend/api/shared/src/main/kotlin/software/medusa/flow/server`. Terraform side:
`.github/config/github-labels.tf`.

## Why field-or-label, not a hard cutover

Issue Fields was in public preview at the time this migration landed, and this change was written
without live access to GitHub's GraphQL schema for it (no network access from the environment that
authored it). Rather than guess the exact query shape and commit to it, the code:

- Reads the `Priority` field when present, and otherwise falls back to the `priority:*` label
  (`IssuePriority.of(fieldValue, labels)` — field wins, unlabeled-and-unset still defaults to
  `Medium`).
- Gates the field *read* behind `GitHubAppCandidateClient.readPriorityField` /
  `GITHUB_ISSUE_FIELDS_ENABLED` (off by default), so the unverified GraphQL fragment can't affect
  production candidate discovery until someone with real API access has confirmed it.
- Degrades safely even once enabled: a GraphQL schema mismatch comes back as HTTP 200 with a
  top-level `errors` array (not a failed status), which `findReadyCandidates` detects and retries
  once with the field fragment omitted, logging a warning. A bad guess at the schema costs a log
  line, not a broken pick loop.

## Cutover steps

1. **De-risk the schema.** Against a real repo with Issue Fields available, run introspection
   (`gh api graphql -f query='{ __type(name: "Issue") { fields { name } } }'`, or the GraphQL
   Explorer) and confirm `issueField(name: String!)` and `IssueFieldSingleSelectValue { name }` (or
   whatever the shape turns out to be) in `GitHubAppCandidateClient`'s query fragment. Fix the
   fragment if it differs — it's isolated to the `priorityFieldFragment` val and the
   `CandidateIssueField`/`CandidateNode.issueField` deserialization at the bottom of the file.
2. **Create the field.** Add a single-select `Priority` Issue Field on the repo (or org, if Issue
   Fields support org-level definitions) with options named exactly `Urgent`, `High`, `Medium`,
   `Low` — `IssuePriority.fieldValue` matches case-insensitively, but keep the display names exact
   for operator sanity. As of this writing `terraform-provider-github` (`~> 6.12.1`, pinned in
   `.github/config/main.tf`) has no resource for Issue Fields, so this is a manual/API step, not
   Terraform-managed; revisit once the provider catches up.
3. **Backfill.** For every open issue carrying a `priority:*` label, set the new field to the same
   tier before relying on it (script against the REST/GraphQL mutation, or by hand for a small
   backlog) — this is what preserves existing issues' priorities through the migration. Do this
   before step 4, not as a side effect of Flow's reconciler (same reason `GitHubIssueClient` never
   auto-deletes a label: an automated bulk write to every open issue is the kind of thing you want a
   deliberate, reviewable, one-time step for).
4. **Flip the flag.** Set `GITHUB_ISSUE_FIELDS_ENABLED=true` per environment. `ReconcilePicker`
   immediately starts ordering by the field where set, falling back to the label elsewhere — safe to
   run with a mixed backlog (partially backfilled) since the fallback covers the gap.
5. **Retire the labels.**
   - `terraform apply` in `.github/config` once the `removed` blocks in `github-labels.tf` are in —
     this drops the four `github_issue_label.priority_*` resources from state (`lifecycle.destroy =
     false`, so nothing plans a destroy) without touching the live labels.
   - Separately, once every open issue's field value is confirmed correct (step 3's backfill,
     verified), delete the four live labels explicitly — e.g. `gh label delete "priority:urgent"
     --yes` (repeat per tier) — as its own reviewed action, not automated. Deleting a label strips it
     off every issue that carries it, irreversibly; this is the same caution `GitHubIssueClient`'s
     module doc already documents for `flow:*`.
6. **Clean up.** Once every environment is past step 5, the label-fallback branch in
   `IssuePriority.of` and the `priority:*`-matching code in `GitHubAppCandidateClient` /
   `CandidateIssue.labels` (priority-specific parts only — `labels` itself still exists for
   `flow:engine=` etc. reads elsewhere) become dead and can be deleted, along with this doc and
   `github-labels.tf`.
