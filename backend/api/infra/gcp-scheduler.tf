# The correctness backstop for the M2 reconciler: a Cloud Scheduler job that
# invokes ReconcileService.Reconcile (no repo arg -> every relevant repo) on a
# fixed cadence. The GitHub webhook (story 10) only *accelerates* this; with
# webhooks disabled the system still converges at this cadence.
#
# This state's CI/CD SA has no serviceusage or cloudscheduler permissions, so the
# foundation pieces live in the (more privileged) root infra state, applied first:
#   - cloudscheduler.googleapis.com enablement (infra/gcp-project.tf)
#   - roles/cloudscheduler.admin on the CI/CD SA (infra/gcp-ci-cd-sa.tf)
# The CI/CD SA's project-level roles/iam.serviceAccountUser covers acting as the
# scheduler SA when creating the job below.

# Dedicated identity the scheduler authenticates as. Like flow-worker, it holds
# no GCP roles: it exists purely so the job can present a Google-signed OIDC
# token that the API's auth decorator verifies and ReconcileService checks
# against its SCHEDULER_SA_EMAILS allowlist (see the env var on
# google_cloud_run_v2_service.primary in main.tf).
resource "google_service_account" "scheduler_sa" {
  project      = var.gcp_project_id
  account_id   = "flow-scheduler"
  display_name = "Flow Reconcile Scheduler Service Account"
}

resource "google_cloud_scheduler_job" "reconcile" {
  project = var.gcp_project_id
  region  = module.common.gcp_primary_location
  name    = "${module.common.gcp_api_run_service_name}-reconcile"

  description = "Periodic full reconcile of every issue pipeline (the reconciler's correctness backstop)."
  schedule    = "*/3 * * * *"
  time_zone   = "Etc/UTC"

  # One reconcile pass is quick; if a run is missed the next tick (or a webhook)
  # covers it, so a single retry and a short deadline are plenty.
  attempt_deadline = "60s"

  retry_config {
    retry_count = 1
  }

  http_target {
    http_method = "POST"

    # The API's gRPC ReconcileService, reached as an unframed request (the server
    # enables them) -- a plain POST of the JSON-encoded request. An empty body
    # ({}) is a ReconcileRequest with no repo filter.
    uri = "${var.worker_token_audience}/medusa.pipeline.v1.ReconcileService/Reconcile"

    headers = {
      "Content-Type" = "application/json"
    }

    body = base64encode("{}")

    # Google-signed OIDC token identifying the scheduler SA. `audience` is the
    # API's own URL -- the `aud` the auth decorator's worker/SA branch expects
    # (WORKER_TOKEN_AUDIENCE); the SA email inside gates access downstream.
    oidc_token {
      service_account_email = google_service_account.scheduler_sa.email
      audience              = var.worker_token_audience
    }
  }
}

output "scheduler_sa_email" {
  description = "Flow reconcile-scheduler service account e-mail (a ReconcileService allowlist value)."
  value       = google_service_account.scheduler_sa.email
}
