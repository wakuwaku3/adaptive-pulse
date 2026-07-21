---
description: Health Connect 同期 (読み取り結果の信頼度・遡及設計) を触るときの注意
paths:
  - "mobile/src/main/java/io/github/wakuwaku3/adaptivepulse/mobile/health/**"
  - "mobile/src/main/java/io/github/wakuwaku3/adaptivepulse/mobile/store/**"
---

# Health Connect 同期

- **HC の読み取り結果は「データが無い」と「読めなかった (レート制限・一時障害)」を区別できない前提で扱う**。全項目 null の日次レコードで、値が入っている既存キャッシュ (Room / Firestore) を REPLACE しない。書き込み前に `DailyHealthRecord.isEmpty` でガードする (Firestore: `FirestoreSync.upsertDailyHealth`、Room: `DashboardRepository.syncDay`)。
- **一度データ入りで確定した過去日は HC から再読しない** (過去日は修正されない、というユーザ決定 2026-07-05)。毎回再読してよいのは通常同期窓 (`NORMAL_WINDOW_DAYS`) だけ。遡及 (initial backfill / Resync) は「行が無い日・空行の日」だけを埋める。
- **大量遡及は HC のレート制限と WorkManager の約 10 分制限で途中停止する前提で設計する**。確定済みの日をスキップして冪等・再開可能にし、1 回の遡及で HC を数万回呼ぶ構造を作らない。
- HC を読めない日への対処はまず再取得 (Resync で空行が埋まる) に寄せる。読み取り失敗を推定値で埋めない (`derived-metrics.md` の実測値の原則と同じ)。
- **HC から読むデータは、大容量の時系列 (HR / Vital サンプル列) を除きすべて Firestore まで届ける** (FB 2026-07-21)。HC には端末を横断してアクセスできないため、Firestore に無いデータは分析に使えない。新しいレコード型・派生ビューを読み始めるときは、表示専用 (Room / メモリ止まり) にせず `DailyHealthRecord` への同梱 (または相当の保存経路) をセットで実装する。時系列を除外するのは容量のため (Spark 無料枠)。例外候補が出たら容量見積りとセットで判断する。確定済みの例外はセッション中 HR (`SessionRecord.hrBpmBySecond`、1 秒グリッドで数 KB〜十数 KB/セッション)。
