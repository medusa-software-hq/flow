# Constants

locals {
  # GitHub Actions integration ID (discovered manually)
  gh_actions_integration_id = 15368
}

# Resources

# This repository
resource "github_repository" "this" {
  name       = local.gh_repo_name
  visibility = "private"

  is_template = false

  has_discussions = false
  has_issues      = false
  has_projects    = false
  has_wiki        = false

  allow_merge_commit = true
  allow_squash_merge = false
  allow_rebase_merge = false

  allow_forking          = true
  allow_auto_merge       = true
  delete_branch_on_merge = true
}

# If the repo was created first, it has to be imported:
# terraform import github_repository.this $GH_REPO_NAME

# Branch protection ruleset for the default branch
resource "github_repository_ruleset" "default_branch" {
  name        = "Default branch"
  repository  = github_repository.this.name
  target      = "branch"
  enforcement = "active"

  conditions {
    ref_name {
      include = ["~DEFAULT_BRANCH"]
      exclude = []
    }
  }

  rules {
    creation                = true
    update                  = false
    deletion                = true
    required_linear_history = false
    required_signatures     = true
    non_fast_forward        = true # Block force pushes

    pull_request {
      allowed_merge_methods = ["merge"]
    }

    required_status_checks {
      required_check {
        context        = "Check root Gradle project"
        integration_id = local.gh_actions_integration_id
      }

      required_check {
        context        = "Check repo Terraform configuration"
        integration_id = local.gh_actions_integration_id
      }

      strict_required_status_checks_policy = true
    }
  }
}

# Allow GitHub Actions from this repository to run
resource "github_actions_repository_permissions" "this" {
  repository      = github_repository.this.name
  enabled         = true
  allowed_actions = "all"
}
