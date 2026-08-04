# Neon (serverless Postgres) backing the counter store.
#
# Each environment owns its OWN project — staging is NOT a branch of prod.
#
# A branch would drag prod's *live rows* (sessions, pipelines, and the github_outbox the reconciler
# drains) into staging, where only an org-fence keeps them from being acted on — a mitigation that
# has to stay correct forever, not a property. The migration-realism a branch is sometimes praised
# for needs prod-*shaped* data (its nulls, dupes, row counts), not prod's actual rows; and Flyway
# builds the schema from empty here regardless. So staging gets a clean, independent database.
#
# If realistic-data migration verification is ever wanted, the right tool is a short-lived branch —
# a golden-seed branch with curated nasty fixtures, or an ephemeral pre-deploy branch of prod that
# lives for minutes in CI and is deleted — never prod data living in a persistent environment.
resource "neon_project" "main" {
  # Per environment: "flow-baseline" on prod (unchanged), "flow-baseline-staging" on staging.
  name      = module.common.subdomain_label
  region_id = "aws-eu-central-1"

  # The provider defaults this to 86400s (24h), which exceeds the Free plan's
  # maximum of 21600s (6h). Point-in-time restore isn't needed for a counter, so
  # pin it to the plan maximum to keep the project provisionable on the Free tier.
  history_retention_seconds = 21600
}

# Undoes the count PR #88 added: prod's project moves back from neon_project.main[0] to
# neon_project.main — same project, unchanged name, so it is a pure state re-association, never a
# destroy/create. (No-op in the staging workspace, whose state has no main[0].)
moved {
  from = neon_project.main[0]
  to   = neon_project.main
}

# JDBC connection string for this environment's own default branch/database/role. We use the direct
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
  database_jdbc_url = join("", [
    "jdbc:postgresql://",
    neon_project.main.database_host,
    "/",
    neon_project.main.database_name,
    "?sslmode=require",
    "&user=", urlencode(neon_project.main.database_user),
    "&password=", urlencode(neon_project.main.database_password),
  ])
}
