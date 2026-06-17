#!/usr/bin/env bash
# 次バージョンを計算して stdout に出す。
# 使い方: next_version.sh <前回tag|空> <major|minor|patch>
# tag は "v1.2.3" "watch-v1.2.3" "phone-v1.0.0" などの任意プレフィックスを許容する。
# 初版 (前回 tag 無し) は 1.0.0 (flame 準拠)。
set -euo pipefail

prior="${1:-}"
bump="${2:-patch}"

if [ -z "$prior" ]; then
  echo "1.0.0"
  exit 0
fi

# 任意プレフィックス (v / watch-v / phone-v) を許容して semver 部分だけ取り出す
IFS=. read -r maj min pat <<<"${prior##*v}"
case "$bump" in
  major) maj=$((maj + 1)); min=0; pat=0 ;;
  minor) min=$((min + 1)); pat=0 ;;
  patch) pat=$((pat + 1)) ;;
  *)
    echo "unknown bump kind: $bump" >&2
    exit 1
    ;;
esac

echo "$maj.$min.$pat"
