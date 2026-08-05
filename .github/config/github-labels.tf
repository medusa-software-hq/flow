# `priority:*` labels — RETIRED. Priority now lives in the native GitHub `Priority` Issue Field
# (see IssuePriority/ReconcilePicker/GitHubAppCandidateClient in backend/api/shared), not a label.
# These `removed` blocks are the safe handoff: applying them unregisters the four resources below
# from Terraform state without touching the live labels (`lifecycle.destroy = false` — the
# declarative equivalent of `terraform state rm`, committed instead of run by hand so it's
# reviewable and repeatable). That keeps `apply` from planning a destroy that would strip the label
# off every issue that still carries it.
#
# Deleting the live labels from the repo is a separate, explicit, one-time step — never a side
# effect of `apply` or of Flow's reconciler (same rule GitHubIssueClient follows for its own
# `flow:*` labels: irreversible, so never automatic) — e.g. `gh label delete "priority:urgent"
# --yes` (repeat per tier), tracked as follow-up org-config cleanup.
#
# Once every environment has applied these `removed` blocks (so the resources are out of every
# workspace's state) and the live labels are deleted, this whole file can be deleted too.

removed {
  from = github_issue_label.priority_urgent
  lifecycle {
    destroy = false
  }
}

removed {
  from = github_issue_label.priority_high
  lifecycle {
    destroy = false
  }
}

removed {
  from = github_issue_label.priority_medium
  lifecycle {
    destroy = false
  }
}

removed {
  from = github_issue_label.priority_low
  lifecycle {
    destroy = false
  }
}
