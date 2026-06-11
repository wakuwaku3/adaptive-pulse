#!/usr/bin/env bash
# PreToolUse(Bash) hook: AI が `git push` しようとした瞬間だけ build+test を走らせる
# (FB ループ 1 層目の境界検査)。CI も同等の検査をするが、push 前にローカルで
# 落とすほうがフィードバックが速い。push 以外の Bash 呼び出しは素通りさせる。
set -uo pipefail

input="$(cat)"
command_line="$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null)"

case "$command_line" in
  *"git push"*) ;;
  *) exit 0 ;;
esac

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

# アプリ scaffold 前は build 対象が無いのでスキップする
if [ ! -x ./gradlew ]; then
  exit 0
fi

if command -v java >/dev/null 2>&1; then
  run() { "$@"; }
elif command -v devbox >/dev/null 2>&1; then
  run() { devbox run -- "$@"; }
else
  echo "java も devbox も見つからないため push 前検査をスキップします" >&2
  exit 0
fi

# build は compile + unit test + lint を含む
if ! build_out="$(run ./gradlew build 2>&1)"; then
  echo "gradlew build 失敗 — push を中止します:" >&2
  echo "$build_out" | tail -n 50 >&2
  exit 2
fi
