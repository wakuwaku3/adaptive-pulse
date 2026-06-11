#!/usr/bin/env bash
# Release Notes を生成して stdout に出す。
# 引数: $1 = 前回 release tag (初版は空)。
# PR を介さず main へ直接 push する運用のため、flame の「label 付き PR 一覧」の
# 代わりに前回 tag → HEAD の commit subject 一覧を Changes とする。
set -euo pipefail

prior="${1:-}"

echo "## Changes"
echo
if [ -n "$prior" ]; then
  git log --no-merges --format='- %s (%h)' "$prior..HEAD"
else
  git log --no-merges --format='- %s (%h)' HEAD
fi
