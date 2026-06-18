# Configuration

locals {
  gh_organization_name = "medusa-software-hq"
  gh_repo_name         = "flow"
}

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "repos/flow"
  }

  required_providers {
    random = {
      source  = "hashicorp/random"
      version = "~> 3.8"
    }
    github = {
      source  = "integrations/github"
      version = "~> 6.12.1"
    }
  }
}

# GitHub provider

variable "gh_token" {
  description = "Organization-owned GitHub token."
  type        = string
  sensitive   = true
}

provider "github" {
  owner = local.gh_organization_name
  token = var.gh_token
}
