# Cloud Run + Artifact Registry を Terraform で宣言する。
# 卵-鶏 (Terraform 実行 SA・tfstate バケット) は infra/bootstrap.sh が事前に作る。

# 必要 API は冪等に enable する (bootstrap でも一部 enable しているが、
# 宣言的管理に寄せるためここでも持つ)
resource "google_project_service" "apis" {
  for_each = toset([
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "firestore.googleapis.com",
    "serviceusage.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "server" {
  location      = var.region
  repository_id = var.artifact_repository_id
  format        = "DOCKER"
  description   = "AdaptivePulse server container images"
  depends_on    = [google_project_service.apis]

  # AR 0.5 GB-月の無料枠を維持するため最新版のみ保持する。
  # 過去 image での即時 rollback は捨てる (必要なら git から再ビルド)。
  cleanup_policy_dry_run = false

  cleanup_policies {
    id     = "keep-latest"
    action = "KEEP"
    most_recent_versions {
      keep_count = 1
    }
  }

  cleanup_policies {
    id     = "delete-others"
    action = "DELETE"
    condition {
      tag_state = "ANY"
    }
  }
}

# Cloud Run の実行 ID (Firestore へのアクセス権はここに付ける。最小権限)
resource "google_service_account" "runtime" {
  account_id   = "${var.service_name}-runtime"
  display_name = "AdaptivePulse server runtime"
}

resource "google_project_iam_member" "runtime_firestore" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

# 初期サービス。本番イメージは deploy-server workflow が更新するため、
# image 変更を tf 側で打ち消さないよう ignore_changes で握る。
resource "google_cloud_run_v2_service" "server" {
  name     = var.service_name
  location = var.region

  template {
    service_account = google_service_account.runtime.email
    containers {
      image = "us-docker.pkg.dev/cloudrun/container/hello"
      env {
        name  = "FIREBASE_PROJECT_ID"
        value = var.project_id
      }
    }
  }

  lifecycle {
    ignore_changes = [
      client,
      client_version,
      template[0].containers[0].image,
    ]
  }

  depends_on = [google_project_service.apis]
}

# 未認証アクセスを許可 (API 自体が Firebase ID トークンで認証するため)
resource "google_cloud_run_v2_service_iam_member" "invoker_all" {
  location = google_cloud_run_v2_service.server.location
  name     = google_cloud_run_v2_service.server.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
