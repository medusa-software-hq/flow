# Configuration

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/flow/baseline/backend/api/foundation"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.25"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.8"
    }
    neon = {
      source  = "kislerdm/neon"
      version = "~> 0.9"
    }
  }
}

# Module imports

module "common" {
  source = "../../infra/common"
}

# Providers

variable "gcp_project_id" {
  description = "GCP project ID."
  type        = string
}

variable "neon_api_key" {
  description = "Neon API key used to provision the serverless Postgres project."
  type        = string
  sensitive   = true
}

variable "worker_token_audience" {
  description = <<-EOT
    The API's own public URL — the `aud` claim a worker's service-account ID
    token is minted with. Sourced from the (manually maintained) API_URL
    GitHub Actions variable, since a Cloud Run service can't cleanly
    self-reference its own computed URL within its own resource config.
  EOT
  type        = string
}

# Primary Google provider
provider "google" {
  project = module.common.gcp_meta_project_id
  region  = module.common.gcp_primary_location
}

# Neon provider for serverless Postgres provisioning
provider "neon" {
  api_key = var.neon_api_key
}

# Dedicated service account for the Cloud Run service.
resource "google_service_account" "primary_service_sa" {
  project      = var.gcp_project_id
  account_id   = "${module.common.gcp_api_run_service_name}-sa"
  display_name = "Cloud Run Service Account"
}

# The primary Cloud Run service for the app
resource "google_cloud_run_v2_service" "primary" {
  project             = var.gcp_project_id
  name                = module.common.gcp_api_run_service_name
  location            = module.common.gcp_primary_location
  deletion_protection = false # This project is experimental
  ingress             = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.primary_service_sa.email

    containers {
      # Initial placeholder; CI/CD will deploy the real image from Artifact Registry.
      image = "us-docker.pkg.dev/cloudrun/container/hello"

      ports {
        container_port = 8080
      }

      env {
        name  = "GOOGLE_CLIENT_ID"
        value = module.common.google_client_id
      }

      # The `flow` CLI's Desktop OAuth client id — a second accepted user-token audience. Empty
      # (module default) until the Desktop client is provisioned; the API treats empty as "no CLI
      # audience", so this deploys safely ahead of the client existing.
      env {
        name  = "CLI_OAUTH_CLIENT_ID"
        value = module.common.cli_oauth_client_id
      }

      env {
        name  = "GOOGLE_ALLOWED_DOMAIN"
        value = module.common.organization_domain
      }

      env {
        name  = "CORS_ALLOWED_ORIGIN_REGEX"
        value = "https://[a-z0-9-]+\\.medusa\\.software"
      }

      env {
        name = "DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database_url.secret_id
            version = "latest"
          }
        }
      }

      env {
        name  = "GITHUB_APP_CLIENT_ID"
        value = module.common.github_app_client_id
      }

      env {
        # The org the deployed App watches/reconciles — per-environment (prod
        # watches medusa-software-hq, staging the sandbox org), NOT the code-repo
        # org. Drives the reconciler's org fence (`org:<owner>` search).
        name  = "GITHUB_REPO_OWNER"
        value = module.common.github_target_org
      }

      env {
        name  = "GITHUB_REPO_NAME"
        value = module.common.gh_repo_name
      }

      env {
        name = "GITHUB_APP_PEM_CONTENT"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.github_app_pem.secret_id
            version = "latest"
          }
        }
      }

      env {
        # WorkerService's allowlist (see gcp-worker-sa.tf). Just the SA's email —
        # not a secret — so a plain env var, matching GOOGLE_CLIENT_ID above.
        name  = "WORKER_SA_EMAILS"
        value = google_service_account.worker_sa.email
      }

      env {
        # ReconcileService's extra allowlist entry: the scheduler SA that drives
        # the periodic backstop reconcile (see gcp-scheduler.tf). The worker SA is
        # also allowed to call Reconcile; main.kt unions the two.
        name  = "SCHEDULER_SA_EMAILS"
        value = google_service_account.scheduler_sa.email
      }

      env {
        name  = "WORKER_TOKEN_AUDIENCE"
        value = var.worker_token_audience
      }

      env {
        name = "GITHUB_WEBHOOK_SECRET"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.github_webhook_secret.secret_id
            version = "latest"
          }
        }
      }
    }
  }

  depends_on = [
    google_secret_manager_secret_version.database_url,
    google_secret_manager_secret_version.github_app_pem,
    google_secret_manager_secret_version.github_webhook_secret,
  ]

  # The image is managed by CI/CD after initial creation.
  # Env vars are managed by Terraform and must not be overwritten by deploys.
  lifecycle {
    # noinspection HILUnresolvedReference
    ignore_changes = [
      template[0].containers[0].image,
      client,
      client_version,
    ]
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }
}

# Allow unauthenticated (public) access — no auth for now.
resource "google_cloud_run_v2_service_iam_member" "public_invoker" {
  project  = google_cloud_run_v2_service.primary.project
  location = google_cloud_run_v2_service.primary.location
  name     = google_cloud_run_v2_service.primary.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

output "cloud_run_primary_service_url" {
  value = google_cloud_run_v2_service.primary.uri
}
