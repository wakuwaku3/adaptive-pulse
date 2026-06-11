---
description: devbox + direnv による開発環境
paths:
  - devbox.json
  - devbox.lock
  - .envrc
---

# 開発環境

- 開発環境は devbox + direnv で再現する。
- ツールは devbox.json に固定する (jdk, gitleaks, jq 等)。
- `.envrc` は devbox の direnv 連携を有効化し、`.env` (gitignore 済) を `dotenv_if_exists` で読み込む。
- repo に入れば `java` 等が PATH に通る状態を維持する。
- Android SDK は devbox では管理しない (導入方法は `docs/stock/tech.md`)。
