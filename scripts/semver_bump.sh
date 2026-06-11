#!/usr/bin/env bash
# 前回 release の公開 surface spec と今回の spec を比較して bump kind を stdout に出す。
# flame の対応付けに準拠:
#   - 同名要素の型変更        → major (破壊的変化)
#   - 要素の追加 (破壊なし)   → minor
#   - それ以外 (単純削除を含む) → patch
# rename は検出せず「削除 + 追加」として minor になる (flame と同じ割り切り)。
# 前回 spec が無い場合 (spec 導入前の release など) は patch にフォールバックする。
set -euo pipefail

prior="$1"
current="$2"

if [ ! -f "$prior" ]; then
  echo "WARN: 前回 spec が見つからないため patch とする" >&2
  echo "patch"
  exit 0
fi

changed="$(jq -r --slurpfile p "$prior" \
  'to_entries[] | select($p[0][.key] != null and $p[0][.key] != .value) | .key' "$current")"
added="$(jq -r --slurpfile p "$prior" \
  'to_entries[] | select($p[0][.key] == null) | .key' "$current")"

if [ -n "$changed" ]; then
  { echo "MAJOR: 型が変化した要素:"; echo "$changed"; } >&2
  echo "major"
elif [ -n "$added" ]; then
  { echo "MINOR: 追加された要素:"; echo "$added"; } >&2
  echo "minor"
else
  echo "patch"
fi
