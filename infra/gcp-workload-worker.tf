# Hosted Flow worker on ms-workload (M4 Path B / B5 staging, B7 prod). Opts the per-environment
# `flow-worker` service account in to being impersonated by the ms-workload broker, and stands up the
# Secret Manager secrets the worker's profile references.
#
# Why root infra/: the impersonation opt-in and secret grants are IAM/serviceusage-adjacent and land
# here per the established permission boundary (the CI/CD SA that applies backend/infra lacks the
# rights). The `flow-worker` SA itself is created in backend/infra; it is referenced here by its
# (stable) resource id — it already exists in both projects, so the binding resolves.

locals {
  is_staging     = module.common.environment == "staging"
  flow_worker_sa = "flow-worker@${local.gcp_project_id}.iam.gserviceaccount.com"

  # The GitHub App PEM the worker reads to open PRs. One App per environment serves *both* the Cloud
  # Run control-plane service and the worker, so prod reuses the control-plane App's PEM
  # (`api-github-app-pem`, created in backend/infra). Staging is the exception: its worker uses a
  # separate, sandbox-owned App (`medusa-flow-nightly`), whose PEM is `flow-worker-github-app-pem`.
  worker_github_app_pem_secret_id = (
    local.is_staging
    ? one(google_secret_manager_secret.flow_worker_github_app_pem[*].id)
    : "projects/${local.gcp_project_id}/secrets/api-github-app-pem"
  )
}

# --- Secrets the worker profile resolves worker-side --------------------------------------------
# Containers only; values are added by hand after apply (`gcloud secrets versions add`), so the
# profile's `.../versions/latest` resolves. The broker never sees the values — the worker reads them
# itself, impersonating flow-worker (grant below), so the read is attributable in Cloud Audit Logs.

# Staging-only: prod's worker reuses the control-plane App's `api-github-app-pem` instead (see the
# local above), so no separate worker-App PEM secret is created for prod.
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
  project   = local.gcp_project_id
  secret_id = "flow-worker-openrouter-api-key"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret" "flow_worker_claude_oauth_token" {
  project   = local.gcp_project_id
  secret_id = "flow-worker-claude-oauth-token"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

# --- Commit-signing key ------------------------------------------------------------------------
# Only the GPG private key is a secret. The other two signing knobs — FLOW_AUTHOR_EMAIL and
# FLOW_ENABLE_GPG_SIGNING — are non-secret configuration and live in the worker's ms-workload
# profile (`envVars`), not here. A container with **no** version; the actual (passphrase-less,
# bot-owned) key is added manually, like the other worker secrets.

resource "google_secret_manager_secret" "flow_worker_gpg_private_key" {
  project   = local.gcp_project_id
  secret_id = "flow-worker-gpg-private-key"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

# --- Broker impersonation opt-in ----------------------------------------------------------------
# The upstream module grants the ms-workload broker `serviceAccountTokenCreator` on flow-worker (so
# it can mint the worker's tokens — including the audience-bound ID token Beacon serves), plus
# `secretAccessor` on exactly these secrets and `artifactregistry.reader` on the repository holding
# the worker image (published per-environment by publish-cli.yml). Deleting this block revokes all
# of it.

module "flow_worker_workload_impersonation" {
  source = "git::ssh://git@github.com/medusa-software-hq/workload.git//infra/modules/workload-impersonation?ref=v1"

  service_account_id    = "projects/${local.gcp_project_id}/serviceAccounts/${local.flow_worker_sa}"
  service_account_email = local.flow_worker_sa

  secret_ids = [
    local.worker_github_app_pem_secret_id,
    google_secret_manager_secret.flow_worker_openrouter_api_key.id,
    google_secret_manager_secret.flow_worker_claude_oauth_token.id,
    google_secret_manager_secret.flow_worker_gpg_private_key.id,
  ]

  artifact_repository_id = google_artifact_registry_repository.primary.id
}

# --- State moves (B7 un-gating) -----------------------------------------------------------------
# B7 dropped `count = local.is_staging ? 1 : 0` from the two secrets and this module to un-gate them
# for prod. Prod created them fresh (nothing was in state there), but the staging state still holds
# them at their old `[0]` address. Without these, an apply on staging would DESTROY the `[0]`
# instances and recreate them un-indexed — deleting the secret *containers* (and their manually
# added versions), and churning the broker/secretAccessor IAM. A `moved` whose `from` isn't in state
# is a no-op, so these are harmless in prod.

moved {
  from = google_secret_manager_secret.flow_worker_openrouter_api_key[0]
  to   = google_secret_manager_secret.flow_worker_openrouter_api_key
}

moved {
  from = google_secret_manager_secret.flow_worker_claude_oauth_token[0]
  to   = google_secret_manager_secret.flow_worker_claude_oauth_token
}

moved {
  from = module.flow_worker_workload_impersonation[0]
  to   = module.flow_worker_workload_impersonation
}
