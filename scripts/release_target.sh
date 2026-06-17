#!/usr/bin/env bash
# release 対象かどうかを form factor 別に判定し、GITHUB_OUTPUT 形式で出力する。
# 使い方: release_target.sh <watch|phone>
#
# 前回 form 別 tag (watch-v* / phone-v*) 以降に該当 module の変更が無い main push
# では release を作らない。form 別 tag が無い (初回) なら常に release する。
# 旧 v* tag は legacy として無視する。
set -euo pipefail

form="${1:-}"
case "$form" in
  watch) paths="app core spec gradle build.gradle.kts settings.gradle.kts gradle.properties" ;;
  phone) paths="mobile core gradle build.gradle.kts settings.gradle.kts gradle.properties" ;;
  *)
    echo "usage: $0 <watch|phone>" >&2
    exit 2
    ;;
esac

prior="$(git tag --list "${form}-v*" --sort=-v:refname | head -n1)"
echo "prior_tag=$prior"

if [ -z "$prior" ]; then
  echo "skip=false"
  exit 0
fi

# shellcheck disable=SC2086 # paths は意図的に分割展開する
changed="$(git diff --name-only "$prior"..HEAD -- $paths 2>/dev/null || true)"
if [ -z "$changed" ]; then
  echo "skip=true"
else
  echo "skip=false"
fi
