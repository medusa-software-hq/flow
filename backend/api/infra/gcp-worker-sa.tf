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

# Lets anyone in the flow-admins@medusa.software Google Group mint short-lived
# credentials as flow-worker via impersonation (no downloaded key ever
# created) — see worker/scripts/get-worker-credentials.sh. Membership is
# managed entirely in Google Groups; nothing here changes per person.
resource "google_service_account_iam_member" "worker_sa_impersonation" {
  service_account_id = google_service_account.worker_sa.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "group:flow-admins@medusa.software"
}

# A downloaded, long-lived JSON key remains available as a fallback (e.g. for
# a hosted worker not running under a caller's own identity) but is
# deliberately not managed by Terraform — a key is a secret, not
# infrastructure:
#
#   gcloud iam service-accounts keys create flow-worker-key.json \
#     --iam-account="flow-worker@<gcp-project-id>.iam.gserviceaccount.com"
#
# Keep the key file out of version control; point the worker at it via
# FLOW_WORKER_SA_KEY_FILE (see worker/README.md).

output "worker_sa_email" {
  description = "Flow worker service account e-mail (the WorkerService allowlist value)."
  value       = google_service_account.worker_sa.email
}
