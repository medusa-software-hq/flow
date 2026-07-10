#!/usr/bin/env bash
set -euo pipefail

# Mints short-lived Application Default Credentials for the flow-worker service
# account via impersonation, so `flow work` can authenticate without a
# downloaded key file (leave FLOW_WORKER_SA_KEY_FILE unset).
#
# Requires membership in the flow-admins@medusa.software Google Group, which
# grants roles/iam.serviceAccountTokenCreator on flow-worker (see
# backend/api/infra/gcp-worker-sa.tf). Ask to be added if this fails with a
# permission error.

project_id="${FLOW_GCP_PROJECT_ID:-ms-flow-b71f4835}"
worker_sa_email="flow-worker@${project_id}.iam.gserviceaccount.com"

echo "Impersonating ${worker_sa_email} (project ${project_id})..." >&2

exec gcloud auth application-default login \
  --impersonate-service-account="${worker_sa_email}"
