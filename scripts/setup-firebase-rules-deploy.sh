#!/usr/bin/env bash
# Firestore Security Rules を CI から deploy するための一回限りのブートストラップ。
# 専用 SA (firebaserules.admin だけ持つ最小権限) を作り、GitHub Actions に渡す。
#
# 前提: gcloud auth login 済み、gh auth login 済み、Firebase プロジェクトが
# 既に作成済み (Spark プランで OK)。Cloud Run / Artifact Registry には依存しない。
# 冪等: 既存 SA は再利用する。
set -euo pipefail

PROJECT_ID="${1:-${ADAPTIVE_PULSE_PROJECT_ID:-}}"

if [ -z "$PROJECT_ID" ]; then
  echo "usage: $0 <firebase-project-id>" >&2
  exit 1
fi

SA_ID="firebase-rules-deployer"
SA_EMAIL="${SA_ID}@${PROJECT_ID}.iam.gserviceaccount.com"
KEY_FILE="$(mktemp)"
trap 'rm -f "$KEY_FILE"' EXIT

echo "==> gcloud のアクティブプロジェクトを設定"
gcloud config set project "$PROJECT_ID" >/dev/null

echo "==> 必須 API を有効化 (firebaserules, firestore, iam)"
gcloud services enable \
  firebaserules.googleapis.com \
  firestore.googleapis.com \
  iam.googleapis.com \
  iamcredentials.googleapis.com >/dev/null

echo "==> Rules deploy 用 SA $SA_EMAIL を用意"
if ! gcloud iam service-accounts describe "$SA_EMAIL" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$SA_ID" --display-name="Firestore Rules deployer (adaptive-pulse)"
fi

echo "==> IAM: roles/firebaserules.admin を付与"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:$SA_EMAIL" --role="roles/firebaserules.admin" --condition=None >/dev/null

echo "==> SA キーを生成"
gcloud iam service-accounts keys create "$KEY_FILE" --iam-account="$SA_EMAIL" >/dev/null

echo "==> GitHub に secret / variable を登録"
gh secret set FIREBASE_RULES_DEPLOYER_KEY < "$KEY_FILE"
gh variable set FIREBASE_PROJECT_ID --body "$PROJECT_ID"

echo
echo "==> 完了。次の操作:"
echo "  firestore.rules を変更して main へ push すると deploy-firestore-rules workflow が apply します。"
echo "  初回 deploy は GitHub Actions タブから 'deploy-firestore-rules' を workflow_dispatch でも可。"
