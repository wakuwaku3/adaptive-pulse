#!/usr/bin/env bash
# 次 release バージョンを決定し、GITHUB_OUTPUT 形式 (key=value) で出力する。
# flame の release policy を単一 Android アプリ向けに適応:
# - 初版は 1.0.0
# - 前回 tag 以降にアプリ実体 (app/ core/ gradle 系) の変更が無ければ skip
#   (docs や CI のみの main push で空 release を作らない)
# - bump は commit message の trailer で決める: `semver: major` > `semver: minor` >
#   無指定 (PATCH)。アプリに公開 API surface が無いため、flame の surface 差分判定の
#   代わりに人間が commit 時に宣言する (規約: CLAUDE.md)
set -euo pipefail

prior="$(git tag --list 'v*' --sort=-v:refname | head -n1)"

if [ -z "$prior" ]; then
  echo "prior_tag="
  echo "version=1.0.0"
  echo "skip=false"
  exit 0
fi

changed="$(git diff --name-only "$prior"..HEAD -- \
  app core gradle build.gradle.kts settings.gradle.kts gradle.properties 2>/dev/null || true)"
if [ -z "$changed" ]; then
  echo "prior_tag=$prior"
  echo "version="
  echo "skip=true"
  exit 0
fi

log="$(git log --format=%B "$prior"..HEAD)"
bump=patch
grep -q '^semver: minor$' <<<"$log" && bump=minor
grep -q '^semver: major$' <<<"$log" && bump=major

IFS=. read -r maj min pat <<<"${prior#v}"
case "$bump" in
  major) maj=$((maj + 1)); min=0; pat=0 ;;
  minor) min=$((min + 1)); pat=0 ;;
  patch) pat=$((pat + 1)) ;;
esac

echo "prior_tag=$prior"
echo "version=$maj.$min.$pat"
echo "skip=false"
