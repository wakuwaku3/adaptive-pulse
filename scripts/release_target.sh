#!/usr/bin/env bash
# release 対象かどうかを判定し、GITHUB_OUTPUT 形式 (key=value) で出力する。
# 前回 tag 以降にアプリ実体 (app/ core/ spec/ gradle 系) の変更が無い main push では
# release を作らない (flame の リリース起動契機 に準拠。docs/CI のみの push で
# 空 release を作らない)。初版 (前回 tag 無し) は常に release する。
set -euo pipefail

prior="$(git tag --list 'v*' --sort=-v:refname | head -n1)"
echo "prior_tag=$prior"

if [ -z "$prior" ]; then
  echo "skip=false"
  exit 0
fi

changed="$(git diff --name-only "$prior"..HEAD -- \
  app core spec gradle build.gradle.kts settings.gradle.kts gradle.properties 2>/dev/null || true)"
if [ -z "$changed" ]; then
  echo "skip=true"
else
  echo "skip=false"
fi
