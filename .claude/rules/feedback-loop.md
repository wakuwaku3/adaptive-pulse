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
- **deploy-firestore-rules workflow** (`.github/workflows/deploy-firestore-rules.yml`): `firestore.rules` / `firebase.json` / `rules-test/` の push (PR / main) で Firebase Emulator + `rules-test/` の挙動テストを走らせ、main push かつ pass で実 Firebase に deploy する。Firebase セットアップ完了まで repo variable `FIREBASE_PROJECT_ID` でゲート (`docs/stock/setup-firebase.md`)。
- **release workflow** (`.github/workflows/release.yml`): main への push を契機に、watch と phone を独立に自動 release する。tag 列は `watch-v*` と `phone-v*` に分かれ、該当 module が前回 form 別 tag 以降に変わったときだけ新版を作る (watch だけ変えれば watch だけ、phone だけなら phone だけ)。旧 `v*` tag は legacy として無視、新採番は各 form の form 別 tag 列から導出する。
  - **対象判定** (`scripts/release_target.sh <watch|phone>`): watch = `app core spec gradle 系` / phone = `mobile core gradle 系` の diff で判定。
  - **bump の自動判定**:
    - watch: `:spec` モジュールが公開 surface (SessionConfig フィールド / SessionEvent / Phase / app manifest の権限・コンポーネント) を JSON spec として生成し、前回 watch release の spec asset と比較する (`scripts/semver_bump.sh`)。同名要素の型変更 = major / 追加 = minor / それ以外 (削除のみ含む) = patch。spec.json は次回判定のため watch release asset に添付する。
    - phone: 公開 surface spec は未導入のため patch 固定。
  - 各 form 初版 1.0.0 (`scripts/next_version.sh`)。Release Notes は form 別前回 tag → HEAD の commit 一覧 (`scripts/release_notes.sh`)。署名済み AAB/APK を添付 (Secrets `ADAPTIVE_PULSE_*`)。versionCode は semver から決定的に導出。`workflow_dispatch` の dry_run で release を作らず経路検証できる。

## 方針

- git 側の lifecycle hook (pre-commit / pre-push) は使わない。検査結果を AI ターン内に返すため hook は Claude Code 側に置く (flame 思想に準拠)。
- hook と CI で検査ロジックを二重実装しない。共通部分は `scripts/` に切り出して両方から呼ぶ (例: `check_docs.sh`)。
- hook はツールが PATH に無くても `devbox run` 経由で動くようにする。
