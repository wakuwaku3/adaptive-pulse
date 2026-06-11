---
description: AI ターン内フィードバックループ・hook・CI の構成
paths:
  - .claude/settings.json
  - scripts/**
  - .github/workflows/**
---

# フィードバックループ

flame の 3 層モデル (AI ターン内 hook / CI / 監視) のうち、本 repo は **AI ターン内 hook + CI** を持つ (public repo なので GitHub Actions を無制限に使える)。監視層は持たず、必要が生じたら検討する。

## 構成

- **Stop hook** (`scripts/check.sh`): 毎ターン・軽量。gitleaks (秘密検査) + ドキュメント配置検査。失敗時 exit 2 で stderr を AI に返し、同ターンで fix させる。Gradle はターン内検査には遅すぎるため呼ばない。
- **PreToolUse hook (Bash matcher)** (`scripts/pre_push.sh`): AI が `git push` しようとした瞬間だけ `./gradlew build` (compile + unit test + lint)。失敗時 exit 2 で push を止める。push 以外の Bash は素通り。`gradlew` が無い間 (アプリ scaffold 前) はスキップ。
- **CI** (`.github/workflows/ci.yml`): push/PR で hook と同等の検査 (gitleaks / docs 配置 / gradle build) を再実行する。手動 push (`! git push`) が hook を素通りする抜け道を塞ぐ層。
- **release workflow** (`.github/workflows/release.yml`): main への push を契機に、flame の release policy のアプリ向け適応で自動 release する。採番は `scripts/next_version.sh` (初版 1.0.0、bump は commit trailer `semver: minor|major`、無指定 PATCH)、Release Notes は `scripts/release_notes.sh` (前回 tag → HEAD の commit 一覧)。アプリ実体 (app/ core/ gradle 系) に変更が無い push は release を作らない。署名済み AAB/APK を添付 (Secrets `ADAPTIVE_PULSE_*`)。versionCode は semver から決定的に導出。`workflow_dispatch` の dry_run で release を作らず経路検証できる。

## 方針

- git 側の lifecycle hook (pre-commit / pre-push) は使わない。検査結果を AI ターン内に返すため hook は Claude Code 側に置く (flame 思想に準拠)。
- hook と CI で検査ロジックを二重実装しない。共通部分は `scripts/` に切り出して両方から呼ぶ (例: `check_docs.sh`)。
- hook はツールが PATH に無くても `devbox run` 経由で動くようにする。
