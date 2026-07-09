# Dedicated identity for the `flow work` worker CLI (M1 stories 04/12-14). It has
# no GCP roles: it exists purely so the worker can mint a Google-signed ID token
# that WorkerService verifies against its WORKER_SA_EMAILS allowlist (see the
# env var on google_cloud_run_v2_service.primary in main.tf). No hosted worker
# exists yet (out of scope for M1), so this only backs a locally-run worker.
resource "google_service_account" "worker_sa" {
  project      = var.gcp_project_id
  account_id   = "flow-worker"
  display_name = "Flow Worker Service Account"
}

# One-time manual step (not managed by Terraform — a downloaded key is a secret,
# not infrastructure): create and download a JSON key for the local worker to
# authenticate as this SA. The account id below matches this file
# (google_service_account.worker_sa.account_id):
#
#   gcloud iam service-accounts keys create flow-worker-key.json \
#     --iam-account="flow-worker@<gcp-project-id>.iam.gserviceaccount.com"
#
# Keep the key file out of version control; point the worker at it via whatever
# credential env var the worker CLI expects (see stories 12-14).

output "worker_sa_email" {
  description = "Flow worker service account e-mail (the WorkerService allowlist value)."
  value       = google_service_account.worker_sa.email
}
