# Hosted Flow worker on ms-workload (M4 Path B / B5) — staging only for now (prod is the stretch
# story B7). Opts the per-environment `flow-worker` service account in to being impersonated by the
# ms-workload broker, and stands up the Secret Manager secrets the worker's profile references.
#
# Why root infra/: the impersonation opt-in and secret grants are IAM/serviceusage-adjacent and land
# here per the established permission boundary (the CI/CD SA that applies backend/api/infra lacks the
# rights). The `flow-worker` SA itself is created in backend/api/infra; it is referenced here by its
# (stable) resource id — it already exists in the staging project, so the binding resolves.

locals {
  is_staging     = module.common.environment == "staging"
  flow_worker_sa = "flow-worker@${local.gcp_project_id}.iam.gserviceaccount.com"
}

# --- Secrets the flow-worker-staging profile resolves worker-side -------------------------------
# Containers only; values are added by hand after apply (`gcloud secrets versions add`), so the
# profile's `.../versions/latest` resolves. The broker never sees the values — the worker reads them
# itself, impersonating flow-worker (grant below), so the read is attributable in Cloud Audit Logs.

resource "google_secret_manager_secret" "flow_worker_github_app_pem" {
  count     = local.is_staging ? 1 : 0
  project   = local.gcp_project_id
  secret_id = "flow-worker-github-app-pem"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret" "flow_worker_openrouter_api_key" {
  count     = local.is_staging ? 1 : 0
  project   = local.gcp_project_id
  secret_id = "flow-worker-openrouter-api-key"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret" "flow_worker_claude_oauth_token" {
  count     = local.is_staging ? 1 : 0
  project   = local.gcp_project_id
  secret_id = "flow-worker-claude-oauth-token"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

# --- Broker impersonation opt-in ----------------------------------------------------------------
# The upstream module grants the ms-workload broker `serviceAccountTokenCreator` on flow-worker (so
# it can mint the worker's tokens — including, once note 02 is live, the audience-bound ID token
# Beacon serves), plus `secretAccessor` on exactly these secrets and `artifactregistry.reader` on the
# repository holding the worker image. Deleting this module block revokes all of it.

module "flow_worker_workload_impersonation" {
  count  = local.is_staging ? 1 : 0
  source = "git::ssh://git@github.com/medusa-software-hq/workload.git//infra/modules/workload-impersonation?ref=v1"

  service_account_id    = "projects/${local.gcp_project_id}/serviceAccounts/${local.flow_worker_sa}"
  service_account_email = local.flow_worker_sa

  secret_ids = [
    google_secret_manager_secret.flow_worker_github_app_pem[0].id,
    google_secret_manager_secret.flow_worker_openrouter_api_key[0].id,
    google_secret_manager_secret.flow_worker_claude_oauth_token[0].id,
  ]

  artifact_repository_id = google_artifact_registry_repository.primary.id
}
