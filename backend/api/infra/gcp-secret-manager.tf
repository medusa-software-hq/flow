# Secret holding the Neon JDBC connection string, injected into Cloud Run.
resource "google_secret_manager_secret" "database_url" {
  project   = var.gcp_project_id
  secret_id = "${module.common.gcp_api_run_service_name}-database-url"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "database_url" {
  secret      = google_secret_manager_secret.database_url.id
  secret_data = local.database_jdbc_url
}

# Allow the Cloud Run service account to read the connection-string secret.
resource "google_secret_manager_secret_iam_member" "primary_service_sa_database_url_accessor" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.database_url.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.primary_service_sa.email}"
}

# Secret holding the GitHub App private key (PEM), injected into Cloud Run.
resource "google_secret_manager_secret" "github_app_pem" {
  project   = var.gcp_project_id
  secret_id = "${module.common.gcp_api_run_service_name}-github-app-pem"

  replication {
    auto {}
  }
}

# Placeholder version; the real PKCS#8 PEM is uploaded manually and must not be
# overwritten by Terraform.
resource "google_secret_manager_secret_version" "github_app_pem" {
  secret      = google_secret_manager_secret.github_app_pem.id
  secret_data = "placeholder"

  lifecycle {
    ignore_changes = [secret_data]
  }
}

# Allow the Cloud Run service account to read the GitHub App private key.
resource "google_secret_manager_secret_iam_member" "primary_service_sa_github_app_pem_accessor" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.github_app_pem.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.primary_service_sa.email}"
}

# Secret holding the GitHub App webhook secret, injected into Cloud Run. The API HMAC-verifies every
# webhook (X-Hub-Signature-256) against this value; the same value is entered in the GitHub App's
# "Webhook secret" field. See backend/api/.../GitHubWebhookService.kt.
resource "google_secret_manager_secret" "github_webhook_secret" {
  project   = var.gcp_project_id
  secret_id = "${module.common.gcp_api_run_service_name}-github-webhook-secret"

  replication {
    auto {}
  }
}

# Placeholder version; the real secret is generated once and uploaded manually (and entered in the
# GitHub App settings), and must not be overwritten by Terraform. While it stays the placeholder,
# real GitHub signatures won't match, so the webhook route rejects everything and the system runs on
# scheduler cadence only.
resource "google_secret_manager_secret_version" "github_webhook_secret" {
  secret      = google_secret_manager_secret.github_webhook_secret.id
  secret_data = "placeholder"

  lifecycle {
    ignore_changes = [secret_data]
  }
}

# Allow the Cloud Run service account to read the webhook secret.
resource "google_secret_manager_secret_iam_member" "primary_service_sa_github_webhook_secret_accessor" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.github_webhook_secret.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.primary_service_sa.email}"
}
