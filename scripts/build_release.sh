#!/usr/bin/env bash
# Play 内部テストに上げる署名済み AAB をビルドする。
# 前提: .env (direnv) に ADAPTIVE_PULSE_KEYSTORE_* が設定済みであること。
# versionCode/versionName は app/build.gradle.kts で手動バンプする。
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -z "${ADAPTIVE_PULSE_KEYSTORE_PATH:-}" ]; then
  # direnv 未活性のシェルからでも動くよう .env を読む
  if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
  else
    echo "ADAPTIVE_PULSE_KEYSTORE_PATH が未設定です (.env を確認)" >&2
    exit 1
  fi
fi

./gradlew :app:bundleRelease

aab="app/build/outputs/bundle/release/app-release.aab"
echo "==> 署名を確認"
keytool -printcert -jarfile "$aab" | head -5
echo "==> 完了: $aab"
echo "Play Console > 内部テスト > 新しいリリース にアップロードしてください"
