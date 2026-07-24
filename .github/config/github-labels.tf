# `priority:*` labels — operator-settable ordering for the `flow:ready` backlog (see
# ReconcilePicker/IssuePriority). Named like Linear's, so the order is unambiguous from the name
# alone. `priority:medium` is the implicit default for an unlabeled ready issue; only label to
# raise (`urgent`/`high`) or defer (`low`).
#
# If any of these labels already exist on the repo (created ad hoc before this file), import-adopt
# them instead of letting `apply` fail on a name collision:
#   terraform import github_issue_label.priority_urgent flow:priority:urgent
# (repeat per label, substituting the GitHub repo name for "flow" if it differs).

resource "github_issue_label" "priority_urgent" {
  repository  = github_repository.this.name
  name        = "priority:urgent"
  color       = "b60205"
  description = "Jump the ready queue — picked before every other priority tier."
}

resource "github_issue_label" "priority_high" {
  repository  = github_repository.this.name
  name        = "priority:high"
  color       = "d93f0b"
  description = "Elevated — picked before medium/low, after urgent."
}

resource "github_issue_label" "priority_medium" {
  repository  = github_repository.this.name
  name        = "priority:medium"
  color       = "fbca04"
  description = "Normal priority — the implicit default for an unlabeled ready issue."
}

resource "github_issue_label" "priority_low" {
  repository  = github_repository.this.name
  name        = "priority:low"
  color       = "c5c5c5"
  description = "Deferred — picked only after every other priority tier is exhausted."
}
