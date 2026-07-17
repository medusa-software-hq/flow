terraform {
  required_version = ">= 1.14"
}

locals {
  # Deployment environment, derived from the Terraform workspace. The `default`
  # workspace is production — its state predates the prod/staging split, so it
  # stays in place (no state migration); every other workspace is a named
  # non-prod environment. This is the single dimension that distinguishes prod
  # from staging across every root that imports this module.
  environment = terraform.workspace == "default" ? "prod" : terraform.workspace

  # Per-environment values. Everything *outside* this map is shared across
  # environments of the same flavor (same GCP meta project, state bucket,
  # region, OAuth client, …); only what genuinely differs per environment lives
  # here. `default`/prod resolves to exactly the values used before this split,
  # so introducing the workspace dimension is a no-op on the prod state.
  environment_config = {
    prod = {
      # GitHub org whose repos this environment's App watches / acts on (the
      # App's target — NOT where CI runs; see gh_organization_name below).
      github_target_org = "medusa-software-hq"

      # GitHub App used to read repository issues.
      # https://github.com/organizations/medusa-software-hq/settings/apps
      github_app_client_id = "Iv23liiYuXXbBb9RRGrZ"

      # Flavor subdomain under organization_domain (e.g. api.<label>.<domain>).
      subdomain_label = "flow-baseline"

      # The GitHub deployment Environment holding this environment's Actions
      # variables (see .github/config). Note prod's is "production", not "prod".
      gh_environment_name = "production"

      # Suffix appended to the GCP project's display name. Empty for prod: its
      # project predates the split and must not be renamed.
      gcp_project_name_suffix = ""

      # Google OAuth 2.0 client ID — the audience of the *user* tokens this
      # environment's API accepts, and the client its SPA signs in with.
      # https://console.cloud.google.com/auth/clients/852264381191-2f485kq98cucbsudhf768ccaadl8ttau.apps.googleusercontent.com?project=ms-auth-284371d2
      google_client_id = "852264381191-2f485kq98cucbsudhf768ccaadl8ttau.apps.googleusercontent.com"
    }
    staging = {
      # Sandbox org — the staging App's credential boundary is the env boundary.
      github_target_org = "medusa-software-test-hq"

      # https://github.com/organizations/medusa-software-test-hq/settings/apps
      github_app_client_id = "Iv23liGENDkcxvvs8EwJ"

      subdomain_label = "flow-baseline-staging"

      gh_environment_name = "staging"

      gcp_project_name_suffix = " - staging"

      # A *separate* OAuth client, whose authorized origin is staging's own domain — not a second
      # origin bolted onto prod's client. The API authenticates a user by checking `aud` against
      # this ID (see GoogleIdTokenAuthDecorator), so a shared client would mean a token minted
      # through staging's SPA is indistinguishable from a production one and accepted by the
      # production API. Staging is where not-yet-promoted code runs; it must not hold a credential
      # production honours. Same rule as the GitHub App and WORKER_TOKEN_AUDIENCE: the credential
      # boundary is the environment boundary.
      # https://console.cloud.google.com/auth/clients/852264381191-rdl0nh865f1m51a7ufkb4i7vbo5nbrqt.apps.googleusercontent.com?project=ms-auth-284371d2
      google_client_id = "852264381191-rdl0nh865f1m51a7ufkb4i7vbo5nbrqt.apps.googleusercontent.com"
    }
  }
  selected_environment = local.environment_config[local.environment]

  organization_domain = "medusa.software"

  gcp_organization_prefix         = "ms"
  gcp_primary_location            = "europe-west1"
  gcp_meta_project_id             = "ms-meta-9aaf29f0"
  gcp_terraform_state_bucket_name = "ms-tfstate-c1984596bdabf023"

  gcp_api_run_service_name = "api"
  gcp_web_run_service_name = "web"

  # The GitHub org + repo that holds the code and runs CI/CD. This is the SAME
  # for every environment — one repo, one Actions pipeline — so it is a flavor
  # constant, NOT part of environment_config. Used for WIF principalSets, the
  # `github` provider owner, and Terraform state prefixes. Do not confuse with
  # github_target_org (the org the deployed App watches), which is per-env.
  gh_organization_name   = "medusa-software-hq"
  gh_repo_name           = "flow"
  gh_api_url_var_name    = "API_URL"
  gh_default_branch_name = "trunk/baseline3"

  github_target_org    = local.selected_environment.github_target_org
  github_app_client_id = local.selected_environment.github_app_client_id

  subdomain_label     = local.selected_environment.subdomain_label
  gh_environment_name = local.selected_environment.gh_environment_name

  # The API's public host, defined once: the domain mapping publishes it (DNS record + Cloud Run
  # mapping), and CI/CD hands it to the service as the audience its workers' ID tokens must be
  # minted for. Two definitions of the same string would silently drift the moment an environment's
  # subdomain changed.
  api_subdomain_name = "api.${local.subdomain_label}"
  api_host_name      = "${local.api_subdomain_name}.${local.organization_domain}"
  api_url            = "https://${local.api_host_name}"

  gcp_project_name_suffix = local.selected_environment.gcp_project_name_suffix

  project_base_name = "flow"
  project_variant   = "baseline"

  # Google OAuth 2.0 client ID — per environment (see environment_config). The clients live in the
  # shared auth project (ms-auth-284371d2), but each environment gets its own: it is the audience of
  # the user tokens that environment's API accepts.
  google_client_id = local.selected_environment.google_client_id
}

output "environment" {
  value = local.environment
}

output "organization_domain" {
  value = local.organization_domain
}

output "gcp_organization_prefix" {
  value = local.gcp_organization_prefix
}

output "gcp_primary_location" {
  value = local.gcp_primary_location
}

output "gcp_meta_project_id" {
  value = local.gcp_meta_project_id
}

output "gcp_terraform_state_bucket_name" {
  value = local.gcp_terraform_state_bucket_name
}

output "gcp_api_run_service_name" {
  value = local.gcp_api_run_service_name
}

output "gcp_web_run_service_name" {
  value = local.gcp_web_run_service_name
}

output "gh_organization_name" {
  value = local.gh_organization_name
}

output "github_target_org" {
  value = local.github_target_org
}

output "subdomain_label" {
  value = local.subdomain_label
}

output "gh_environment_name" {
  value = local.gh_environment_name
}

output "api_subdomain_name" {
  value = local.api_subdomain_name
}

output "api_host_name" {
  value = local.api_host_name
}

output "api_url" {
  value = local.api_url
}

output "gcp_project_name_suffix" {
  value = local.gcp_project_name_suffix
}

output "gh_repo_name" {
  value = local.gh_repo_name
}

output "gh_default_branch_name" {
  value = local.gh_default_branch_name
}

output "gh_api_url_var_name" {
  value = local.gh_api_url_var_name
}

output "github_app_client_id" {
  value = local.github_app_client_id
}

output "project_base_name" {
  value = local.project_base_name
}

output "project_variant" {
  value = local.project_variant
}

output "google_client_id" {
  value = local.google_client_id
}
