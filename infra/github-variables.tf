# Actions variables consumed by CI/CD jobs.
#
# Migration in flight (M2.5): the end state is that *both* environments read
# Environment-scoped variables, so prod and staging each get their own project id,
# API URL and CI/CD identity. It is staged so prod never loses its variable source:
#
#   1. (this change) add Environment-scoped variables for both environments, and
#      keep the repo-level ones — workflows declare no `environment:` yet, so they
#      still read the repo-level values and prod is untouched;
#   2. flip the workflows to a [production, staging] matrix with `environment:`;
#   3. drop the repo-level variables below, once nothing reads them.
#
# The repo-level variables are prod-only: they are a single repo-wide namespace, so
# a staging-workspace apply must never write staging values into them.

locals {
  is_prod = module.common.environment == "prod"

  # Everything the CI/CD jobs read, per environment. API_URL is excluded — it is
  # manually managed and needs its own lifecycle (below).
  cicd_environment_variables = {
    GCP_PROJECT_ID           = google_project.gcp_project.project_id
    GCP_PRIMARY_LOCATION     = module.common.gcp_primary_location
    GCP_API_RUN_SERVICE_NAME = module.common.gcp_api_run_service_name
    GCP_WEB_RUN_SERVICE_NAME = module.common.gcp_web_run_service_name
    GCP_CICD_SA_EMAIL        = google_service_account.cicd_sa.email
    GCP_AR_REPO_HOSTNAME     = split("/", google_artifact_registry_repository.primary.registry_uri)[0]
    GCP_AR_REPO_ENDPOINT     = local.gcp_ar_repo_endpoint
    GOOGLE_CLIENT_ID         = module.common.google_client_id
    GOOGLE_ALLOWED_DOMAIN    = module.common.organization_domain
  }
}

# region Environment-scoped variables (the destination of the migration)

# Each workspace writes only its own environment's variables: the prod workspace
# fills `production`, the staging workspace fills `staging`.
resource "github_actions_environment_variable" "cicd" {
  for_each = local.cicd_environment_variables

  repository    = data.github_repository.this.name
  environment   = module.common.gh_environment_name
  variable_name = each.key
  value         = each.value
}

# The API's own public URL. Terraform provisions the variable (so the name is never
# a typo) but never owns the value — it is set by hand once the service has a URL.
resource "github_actions_environment_variable" "api_url" {
  repository    = data.github_repository.this.name
  environment   = module.common.gh_environment_name
  variable_name = module.common.gh_api_url_var_name

  value = "https://example.com/placeholder"

  lifecycle {
    ignore_changes = [value]
  }
}

# endregion

# region Repo-level variables (legacy — removed in step 3 of the migration)

moved {
  from = github_actions_variable.gcp_project_id
  to   = github_actions_variable.gcp_project_id[0]
}

resource "github_actions_variable" "gcp_project_id" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GCP_PROJECT_ID"
  value         = google_project.gcp_project.project_id
}

moved {
  from = github_actions_variable.gcp_primary_location
  to   = github_actions_variable.gcp_primary_location[0]
}

resource "github_actions_variable" "gcp_primary_location" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GCP_PRIMARY_LOCATION"
  value         = module.common.gcp_primary_location
}

moved {
  from = github_actions_variable.gcp_api_run_service_name
  to   = github_actions_variable.gcp_api_run_service_name[0]
}

resource "github_actions_variable" "gcp_api_run_service_name" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GCP_API_RUN_SERVICE_NAME"
  value         = module.common.gcp_api_run_service_name
}

moved {
  from = github_actions_variable.gcp_web_run_service_name
  to   = github_actions_variable.gcp_web_run_service_name[0]
}

resource "github_actions_variable" "gcp_web_run_service_name" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GCP_WEB_RUN_SERVICE_NAME"
  value         = module.common.gcp_web_run_service_name
}

moved {
  from = github_actions_variable.gcp_cicd_sa_email
  to   = github_actions_variable.gcp_cicd_sa_email[0]
}

resource "github_actions_variable" "gcp_cicd_sa_email" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GCP_CICD_SA_EMAIL"
  value         = google_service_account.cicd_sa.email
}

moved {
  from = github_actions_variable.gcp_ar_repo_hostname
  to   = github_actions_variable.gcp_ar_repo_hostname[0]
}

resource "github_actions_variable" "gcp_ar_repo_hostname" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GCP_AR_REPO_HOSTNAME"
  value         = split("/", google_artifact_registry_repository.primary.registry_uri)[0]
}

moved {
  from = github_actions_variable.gcp_ar_repo_endpoint
  to   = github_actions_variable.gcp_ar_repo_endpoint[0]
}

resource "github_actions_variable" "gcp_ar_repo_endpoint" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GCP_AR_REPO_ENDPOINT"
  value         = local.gcp_ar_repo_endpoint
}

moved {
  from = github_actions_variable.gcp_api_url
  to   = github_actions_variable.gcp_api_url[0]
}

resource "github_actions_variable" "gcp_api_url" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = module.common.gh_api_url_var_name

  # Managed manually (for now):
  # https://github.com/medusa-software-hq/flow/settings/variables/actions/API_URL
  value = "https://example.com/placeholder"

  lifecycle {
    ignore_changes = [value]
  }
}

moved {
  from = github_actions_variable.google_client_id
  to   = github_actions_variable.google_client_id[0]
}

# Consumed by the web frontend build (baked into the JS bundle).
resource "github_actions_variable" "google_client_id" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GOOGLE_CLIENT_ID"
  value         = module.common.google_client_id
}

moved {
  from = github_actions_variable.google_allowed_domain
  to   = github_actions_variable.google_allowed_domain[0]
}

resource "github_actions_variable" "google_allowed_domain" {
  count = local.is_prod ? 1 : 0

  repository    = data.github_repository.this.name
  variable_name = "GOOGLE_ALLOWED_DOMAIN"
  value         = module.common.organization_domain
}

# endregion

# No github_actions_variable/secret for the GitHub App client ID / PEM: GitHub
# rejects variable/secret names starting with "GITHUB_", and neither is
# actually consumed by any workflow — the client ID is a hardcoded local
# (module.common.github_app_client_id, wired straight into the Cloud Run env
# var in backend/api/infra/main.tf) and the PEM is a manually-uploaded Secret
# Manager placeholder (backend/api/infra/gcp-secret-manager.tf).
#
# GCP_CICD_WI_PROVIDER_NAME is likewise absent here: the Workload Identity pool is
# shared across environments, so both read the same repo-level value.
