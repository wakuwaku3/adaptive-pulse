#!/usr/bin/env bash
# Stop hook: AI がターンを返す前に走る軽量検査 (FB ループ 1 層目)。
# 失敗時は exit 2 で stderr を AI に返し、同一ターン内で fix させる。
# Gradle はターン内検査には遅すぎるため呼ばない (push hook と CI が担う)。
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

fail=0

# ドキュメント配置検査 (CI と共通のスクリプト)
if ! bash scripts/check_docs.sh; then
  fail=1
fi

# secret 検査: public repo への秘密情報混入を最重要で防ぐ (.claude/rules/secret.md)。
# commit 前に検出したいので git 履歴ではなく dir (作業ツリー) を走査する。
# gitignore 済の秘密ファイルは .gitleaks.toml の allowlist で除外している。
if command -v gitleaks >/dev/null 2>&1; then
  run_leaks() { gitleaks "$@"; }
elif command -v devbox >/dev/null 2>&1; then
  run_leaks() { devbox run -- gitleaks "$@"; }
else
  echo "gitleaks も devbox も見つからないため secret 検査をスキップします" >&2
  run_leaks() { true; }
fi

if leaks_out="$(run_leaks dir --no-banner --redact . 2>&1)"; then
  : # クリーン
else
  echo "gitleaks: 秘密情報の混入を検出しました:" >&2
  echo "$leaks_out" >&2
  fail=1
fi

[ "$fail" -eq 0 ] && exit 0 || exit 2
