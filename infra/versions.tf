terraform {
  required_version = ">= 1.9.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.12"
    }
  }

  # state は GCS。bucket 名は bootstrap で作成済みで、`-backend-config` で渡す
  backend "gcs" {
    prefix = "adaptive-pulse"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
