#!/usr/bin/env bash
# Terraform 実行の卵-鶏問題を解消するための一回限りのブートストラップ。
# Cloud Run / Artifact Registry / IAM 本体は Terraform で宣言的に管理し、
# このスクリプトは「Terraform を回すための土台」だけを作る:
#   - 必須 API の有効化
#   - tfstate を置く GCS バケット (versioning on)
#   - Terraform 実行用サービスアカウントとキー
#   - GitHub Actions の secret / variable への登録
#
# 前提: gcloud auth login 済み、gh auth login 済み。
# 冪等: 既存リソースは再利用する (set -e でも継続するため runCatching 相当の判定を入れる)。
set -euo pipefail

PROJECT_ID="${1:-${ADAPTIVE_PULSE_PROJECT_ID:-}}"
REGION="${2:-asia-northeast1}"

if [ -z "$PROJECT_ID" ]; then
  echo "usage: $0 <gcp-project-id> [region]" >&2
  exit 1
fi

BUCKET="${PROJECT_ID}-tfstate"
SA_ID="tf-runner"
SA_EMAIL="${SA_ID}@${PROJECT_ID}.iam.gserviceaccount.com"
KEY_FILE="$(mktemp)"
trap 'rm -f "$KEY_FILE"' EXIT

echo "==> gcloud のアクティブプロジェクトを設定"
gcloud config set project "$PROJECT_ID" >/dev/null

echo "==> 必須 API を有効化"
gcloud services enable \
  serviceusage.googleapis.com \
  cloudresourcemanager.googleapis.com \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  storage.googleapis.com >/dev/null

echo "==> tfstate バケット gs://$BUCKET を用意"
if ! gcloud storage buckets describe "gs://$BUCKET" >/dev/null 2>&1; then
  gcloud storage buckets create "gs://$BUCKET" --location="$REGION" --uniform-bucket-level-access
fi
gcloud storage buckets update "gs://$BUCKET" --versioning >/dev/null

echo "==> Terraform 実行用 SA $SA_EMAIL を用意"
if ! gcloud iam service-accounts describe "$SA_EMAIL" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$SA_ID" --display-name="Terraform runner (adaptive-pulse)"
fi

# 個人プロジェクトでは Owner で妥協する。最小権限化は将来の課題 (docs/stock/setup-firebase.md)
echo "==> IAM: Owner ロールを付与"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:$SA_EMAIL" --role="roles/owner" --condition=None >/dev/null

echo "==> SA キーを生成"
gcloud iam service-accounts keys create "$KEY_FILE" --iam-account="$SA_EMAIL" >/dev/null

echo "==> GitHub に secret / variable を登録"
gh secret set GCP_SA_KEY < "$KEY_FILE"
gh variable set GCP_PROJECT_ID --body "$PROJECT_ID"
gh variable set GCP_REGION --body "$REGION"
gh variable set TFSTATE_BUCKET --body "$BUCKET"

echo
echo "==> 完了。次の操作:"
echo "  1. GitHub の Actions タブで 'terraform' workflow を手動実行 (apply) するか、"
echo "     infra/ を変更して main へ push すると CI が apply します。"
echo "  2. apply 完了後、Cloud Run の URL を SERVER_BASE_URL に設定:"
echo "     (cd infra && terraform output -raw service_url) | xargs -I{} gh variable set SERVER_BASE_URL --body '{}'"
echo "     (ローカル plan/apply には CI と同じ tf-runner キーが必要。Actions タブからの実行を推奨)"
