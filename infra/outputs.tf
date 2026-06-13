output "service_url" {
  description = "Cloud Run のサービス URL (phone アプリの SERVER_BASE_URL に設定する)"
  value       = google_cloud_run_v2_service.server.uri
}

output "artifact_repository" {
  description = "Artifact Registry リポジトリのパス"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.server.repository_id}"
}
