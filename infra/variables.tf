variable "project_id" {
  description = "Firebase / GCP プロジェクト ID"
  type        = string
}

variable "region" {
  description = "Cloud Run / Artifact Registry のリージョン"
  type        = string
  default     = "asia-northeast1"
}

variable "service_name" {
  description = "Cloud Run サービス名 (deploy-server workflow と一致させる)"
  type        = string
  default     = "adaptive-pulse-server"
}

variable "artifact_repository_id" {
  description = "Artifact Registry リポジトリ名 (deploy-server workflow と一致させる)"
  type        = string
  default     = "adaptive-pulse"
}
