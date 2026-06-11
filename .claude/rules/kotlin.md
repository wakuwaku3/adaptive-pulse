---
description: Kotlin / Android コードを書くときの構成・抽象化
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# Kotlin コードを書くときの注意

着手前に `docs/stock/requirements.md` と `docs/stock/tech.md` を読む。コメント (Why のみ) 等の常時原則は CLAUDE.md にある。ここでは **Kotlin を書くときの構成**だけを書く。

- フェーズ遷移・サイクルカウント・疲労ブレーキ等のドメインロジックは Android 非依存の純 Kotlin に置き、JVM 単体テストだけで検証できる状態を保つ (エミュレータ・実機を要求しない)。
- 外部依存 (心拍ソース・時計・振動・永続化) は interface で抽象化し、テストで fake に差し替え可能にする。
- 心拍ソース (Health Services / BLE H10 / 合成データ) は同一 interface の実装として扱い、ロジック側からは区別しない。
