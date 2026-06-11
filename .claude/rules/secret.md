---
description: 秘密情報・個人データの取り扱い (public repo・常時適用)
---

# 秘密情報の取り扱い (最重要)

本 repo は public。コミットした瞬間に全世界へ公開される前提で扱う。

- 秘密情報 (トークン・署名鍵 keystore・認証情報) を repo に**絶対にコミットしない**。コード・設定・テストデータ・ドキュメントへの直書きを禁止する。
- 必要な秘密は環境変数経由でのみ受け取る。ローカルは `.env` (gitignore 済) を direnv で読み込む。命名は `ADAPTIVE_PULSE_<用途>` で統一する。
- 健康データ (実測の心拍ログ等) はユーザ個人のものなので repo にコミットしない。テストデータは合成する。
- 秘密を含みうるファイル (`.env`, `*.keystore`, `local.properties` 等) は `.gitignore` で除外する。
- gitleaks による検査を Stop hook と CI に組み込み、混入を検出する。
