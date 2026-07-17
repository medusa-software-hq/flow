locals {
  is_prod = module.common.environment == "prod"
}

# Neon (serverless Postgres) backing the counter store.
#
# Prod owns the project; every other environment is a *branch* of it rather than a project of its
# own, so staging starts from prod's schema (copy-on-write) and exercises migrations against a
# prod-shaped database. Only the prod workspace creates the project — other workspaces read its id
# out of prod's state and branch from there.
resource "neon_project" "main" {
  count = local.is_prod ? 1 : 0

  name      = "${module.common.project_base_name}-${module.common.project_variant}"
  region_id = "aws-eu-central-1"

  # The provider defaults this to 86400s (24h), which exceeds the Free plan's
  # maximum of 21600s (6h). Point-in-time restore isn't needed for a counter, so
  # pin it to the plan maximum to keep the project provisionable on the Free tier.
  history_retention_seconds = 21600
}

moved {
  from = neon_project.main
  to   = neon_project.main[0]
}

# The project lives in the prod workspace's state, so every other environment reads it from there.
# Guarded by count: the prod workspace must not read the state it is itself writing.
data "terraform_remote_state" "prod_api" {
  count = local.is_prod ? 0 : 1

  backend   = "gcs"
  workspace = "default"

  config = {
    bucket = module.common.gcp_terraform_state_bucket_name
    prefix = "projects/${module.common.project_base_name}/${module.common.project_variant}/backend/api/foundation"
  }
}

locals {
  # The Neon project every environment shares: owned by prod, branched by the rest.
  neon_project_id = (
    local.is_prod
    ? one(neon_project.main[*].id)
    : data.terraform_remote_state.prod_api[0].outputs.neon_project_id
  )

  # Databases are inherited by a branch, so the name comes from prod either way.
  neon_database_name = (
    local.is_prod
    ? one(neon_project.main[*].database_name)
    : data.terraform_remote_state.prod_api[0].outputs.neon_database_name
  )
}

# region Non-prod branch

# Branches off the project's default branch (no parent_id) at the moment of creation.
resource "neon_branch" "environment" {
  count = local.is_prod ? 0 : 1

  project_id = local.neon_project_id
  name       = module.common.environment
}

# A branch is unreachable until it has a compute endpoint; only one read_write is allowed per branch.
resource "neon_endpoint" "environment" {
  count = local.is_prod ? 0 : 1

  project_id = local.neon_project_id
  branch_id  = one(neon_branch.environment[*].id)
  type       = "read_write"
}

# The branch inherits prod's roles, but this environment gets its own credentials rather than
# carrying prod's password into a second state and a second Secret Manager.
resource "neon_role" "environment" {
  count = local.is_prod ? 0 : 1

  project_id = local.neon_project_id
  branch_id  = one(neon_branch.environment[*].id)
  name       = "${module.common.environment}_app"
}

# endregion

# JDBC connection string for this environment's branch/database/role. We use the direct
# (non-pooled) endpoint (`database_host`, not `database_host_pooler`) so Flyway's
# session-level advisory lock works reliably at startup; PgBouncer's transaction
# pooling would make that lock unreliable. The per-instance Hikari pool is small and
# Cloud Run runs few instances, so direct connections are well within Neon's limits.
#
# We build a native pgjdbc URL from Neon's structured attributes rather than reusing
# its `connection_uri`. pgjdbc does NOT accept libpq-style `user:password@host`
# userinfo (it rejects the URL in acceptsURL); credentials must be host/database in
# the URL with user/password as query parameters. `sslmode=require` is mandatory for
# Neon, and `urlencode` guards against special characters in the generated password.
locals {
  database_host = (
    local.is_prod
    ? one(neon_project.main[*].database_host)
    : one(neon_endpoint.environment[*].host)
  )

  database_user = (
    local.is_prod
    ? one(neon_project.main[*].database_user)
    : one(neon_role.environment[*].name)
  )

  database_password = (
    local.is_prod
    ? one(neon_project.main[*].database_password)
    : one(neon_role.environment[*].password)
  )

  database_jdbc_url = join("", [
    "jdbc:postgresql://",
    local.database_host,
    "/",
    local.neon_database_name,
    "?sslmode=require",
    "&user=", urlencode(local.database_user),
    "&password=", urlencode(local.database_password),
  ])
}

# Outputs

# Read by every non-prod workspace to branch from (see data.terraform_remote_state.prod_api).
output "neon_project_id" {
  description = "Neon project ID (owned by the prod workspace; branched by every other environment)."
  value       = local.neon_project_id
}

output "neon_database_name" {
  description = "Neon database name, inherited from prod by every branch."
  value       = local.neon_database_name
}
