#!/usr/bin/env bash
# ドキュメント配置検査: すべての .md は stock か flow のいずれかの許可場所に置く
# (.claude/rules/document.md)。それ以外の場所への .md は違反として弾く。
# Stop hook と CI の両方から呼ばれる (検査ロジックの二重実装を避ける)。
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

stray=""
while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in
    README.md | */README.md) ;;        # stock: プロジェクト概要
    CLAUDE.md | */CLAUDE.md) ;;         # stock: repo ルール stub
    .claude/*) ;;                      # stock: 開発ルール等
    docs/stock/*) ;;                   # stock: 確定ドキュメント
    docs/notes/*) ;;                   # flow: 検討経緯・ジャーナル
    *) stray="${stray}${f}"$'\n' ;;
  esac
done < <(git ls-files --cached --others --exclude-standard -- '*.md' 2>/dev/null)

if [ -n "$stray" ]; then
  echo "ドキュメント配置違反: .md は stock(README / CLAUDE.md / .claude/ / docs/stock/) か flow(docs/notes/) に置くこと:" >&2
  printf '%s' "$stray" >&2
  exit 2
fi
