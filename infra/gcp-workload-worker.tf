# Hosted Flow worker on ms-workload (M4 Path B / B5 staging, B7 prod). Opts the per-environment
# `flow-worker` service account in to being impersonated by the ms-workload broker, and stands up the
# Secret Manager secrets the worker's profile references.
#
# Why root infra/: the impersonation opt-in and secret grants are IAM/serviceusage-adjacent and land
# here per the established permission boundary (the CI/CD SA that applies backend/api/infra lacks the
# rights). The `flow-worker` SA itself is created in backend/api/infra; it is referenced here by its
# (stable) resource id — it already exists in both projects, so the binding resolves.

locals {
  is_staging     = module.common.environment == "staging"
  flow_worker_sa = "flow-worker@${local.gcp_project_id}.iam.gserviceaccount.com"

  # The GitHub App PEM the worker reads to open PRs. One App per environment serves *both* the Cloud
  # Run control-plane service and the worker, so prod reuses the control-plane App's PEM
  # (`api-github-app-pem`, created in backend/api/infra). Staging is the exception: its worker uses a
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

# --- Commit-signing config + key (FLOW_AUTHOR_EMAIL / FLOW_ENABLE_GPG_SIGNING / the GPG key) -------
# The worker resolves all three via its profile's `secretEnvVars`. author-email and enable-gpg-signing
# get a Terraform-seeded *placeholder* version so the worker has a working default on day one; both
# carry `ignore_changes = [secret_data]` so an operator can flip signing on / change the author out of
# band without Terraform reverting it (and without the value living in this repo). The GPG private key
# is a container with **no** version — the actual (passphrase-less, bot-owned) key is added manually,
# like the other worker secrets.

resource "google_secret_manager_secret" "flow_worker_author_email" {
  project   = local.gcp_project_id
  secret_id = "flow-worker-author-email"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret_version" "flow_worker_author_email_placeholder" {
  secret      = google_secret_manager_secret.flow_worker_author_email.id
  secret_data = "flow@medusa.software"

  lifecycle {
    ignore_changes = [secret_data]
  }
}

resource "google_secret_manager_secret" "flow_worker_enable_gpg_signing" {
  project   = local.gcp_project_id
  secret_id = "flow-worker-enable-gpg-signing"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

resource "google_secret_manager_secret_version" "flow_worker_enable_gpg_signing_default" {
  secret      = google_secret_manager_secret.flow_worker_enable_gpg_signing.id
  secret_data = "false"

  lifecycle {
    ignore_changes = [secret_data]
  }
}

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
    google_secret_manager_secret.flow_worker_author_email.id,
    google_secret_manager_secret.flow_worker_enable_gpg_signing.id,
    google_secret_manager_secret.flow_worker_gpg_private_key.id,
  ]

  artifact_repository_id = google_artifact_registry_repository.primary.id
}
