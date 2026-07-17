# Actions secrets whose values come from outside Terraform.
#
# Terraform owns the secret's *name*, never its value. GitHub never returns a secret's value, so
# there is nothing to reconcile — but the name is worth managing: a typo'd secret name fails
# silently (the workflow reads an empty string and the job dies somewhere further along). So the
# resource pins the name, `value` is only ever written when Terraform creates a secret that does not
# exist yet, and `ignore_changes` keeps Terraform's hands off the real value thereafter.
#
# Existing secrets are adopted with `import` for that reason: creating them outright would PUT the
# placeholder over a live credential. The import blocks are idempotent once the secrets are in
# state, and can be dropped after the first successful apply.
#
# ORG-LEVEL SECRETS ARE DELIBERATELY ABSENT. `NEON_API_KEY`, `CLOUDFLARE_API_TOKEN`, `CLOUDSMITH_*`
# and `GH_APP_CICD_HELPER_READ_ONLY_PEM_CONTENT` are organization secrets inherited by this repo
# (`GET /repos/{owner}/{repo}/actions/organization-secrets`), shared with other repos and owned by
# the organization's own configuration. Declaring them here would create *repository* secrets of the
# same name, which silently **shadow** the organization's — repository scope wins — handing every
# workflow this placeholder instead of the real credential.

locals {
  # Never reaches a live secret: only used on creation of a not-yet-existing one, whose real value
  # is then set by hand.
  secret_placeholder = "placeholder"
}

# Read by the hermetic loop test (and the nightly) to run the engine on cheap models. Budget-capped
# on OpenRouter, and scoped to engine tests by name so it is not mistaken for an app credential.
resource "github_actions_secret" "engine_tests_openrouter_api_key" {
  repository  = github_repository.this.name
  secret_name = "ENGINE_TESTS_OPENROUTER_API_KEY"
  value       = local.secret_placeholder

  lifecycle {
    ignore_changes = [value]
  }
}

import {
  to = github_actions_secret.engine_tests_openrouter_api_key
  id = "${module.common.gh_repo_name}:ENGINE_TESTS_OPENROUTER_API_KEY"
}

# The releaser App's private key, used by Publish CLI to cut releases.
resource "github_actions_secret" "gh_releases_app_pem_content" {
  repository  = github_repository.this.name
  secret_name = "GH_RELEASES_APP_PEM_CONTENT"
  value       = local.secret_placeholder

  lifecycle {
    ignore_changes = [value]
  }
}

import {
  to = github_actions_secret.gh_releases_app_pem_content
  id = "${module.common.gh_repo_name}:GH_RELEASES_APP_PEM_CONTENT"
}
