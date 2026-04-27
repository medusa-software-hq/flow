terraform {
  required_version = ">= 1.14"
}

locals {
  organization_domain = "medusa.software"

  gh_organization_name = "medusa-software-hq"
  gh_repo_name         = "flow"

  project_base_name = "flow"
  project_variant   = "baseline"
}

output "organization_domain" {
  value = local.organization_domain
}

output "gh_organization_name" {
  value = local.gh_organization_name
}

output "gh_repo_name" {
  value = local.gh_repo_name
}

output "project_base_name" {
  value = local.project_base_name
}

output "project_variant" {
  value = local.project_variant
}
