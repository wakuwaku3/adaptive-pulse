---
description: Health Connect 同期 (読み取り結果の信頼度・遡及設計) を触るときの注意
paths:
  - "mobile/src/main/java/io/github/wakuwaku3/adaptivepulse/mobile/health/**"
  - "mobile/src/main/java/io/github/wakuwaku3/adaptivepulse/mobile/store/**"
---

# Health Connect 同期

- **「データが無い」と「読めなかった (レート制限・一時障害)」は読み取り例外の有無で区別する** (`SnapshotResult.readFailed`)。例外ゼロのクリーン読みは HC の現状そのものなので、**空でも上書きして HC 側で削除されたデータをキャッシュ (Room / Firestore) に伝播させる** (FB 2026-07-21)。例外が出た読みは既存キャッシュを温存する。Room はクリーン読みの行に `verifiedAtMs` を刻み、Firestore の空上書きは検証済み行だけ許す (`FirestoreSync.upsertDailyHealth(allowEmpty)`)。`isEmpty` 単体では上書き可否を判断しない。
- **通常の遡及 (自動再 backfill = fill モード) は「実測データ入り」で確定した過去日を再読しない** (過去日は修正されない、というユーザ決定 2026-07-05)。確定判定は `DailyHealthRecord.hasMeasuredData` を使う: HC は実レコードが無い日でも BMR 由来カロリー (total/basal) を合成して返すため、それだけの行を確定扱いすると「読めていないのに二度と再読されない日」が生まれる (2026-07-21 に体重欠落として顕在化)。
- **手動 Resync と履歴権限付与後は HC を正として全日を再読する** (authoritative モード。FB 2026-07-21、2026-07-05 決定の更新)。確定済みの日も読み直し、HC 側の削除を反映する。再開可能性は「Resync 要求時刻以降に `verifiedAtMs` が付いた日」のスキップで担保する。
- **TDEE / exerciseExtra は本アプリがマスター**。HC の total を信頼せず `CalorieEnricher` が毎回再計算する既存経路を、どのモードの再読でも維持する (HC 値で上書きしない)。
- **大量遡及は HC のレート制限と WorkManager の約 10 分制限で途中停止する前提で設計する**。確定済みの日をスキップして冪等・再開可能にし、1 回の遡及で HC を数万回呼ぶ構造を作らない。
- **Firestore への反映も途中停止に耐える構造にする**。「1 回の worker 実行で読んだ日を実行完了時にまとめて上げる」ような実行内メモリ依存は、停止した実行の分が永久に欠落する (2026-07-21 の欠落の直接原因)。Room の行に `uploadedAtMs` マークを持ち、どの worker (通常/遡及) も未反映行を flush する (`DashboardRepository.flushUnuploaded`)。行を書き直したらマークを null に戻して再反映対象にする。
- HC を読めない日への対処はまず再取得 (Resync で空行が埋まる) に寄せる。読み取り失敗を推定値で埋めない (`derived-metrics.md` の実測値の原則と同じ)。
- **HC から読むデータは、大容量の時系列 (HR / Vital サンプル列) を除きすべて Firestore まで届ける** (FB 2026-07-21)。HC には端末を横断してアクセスできないため、Firestore に無いデータは分析に使えない。新しいレコード型・派生ビューを読み始めるときは、表示専用 (Room / メモリ止まり) にせず `DailyHealthRecord` への同梱 (または相当の保存経路) をセットで実装する。時系列を除外するのは容量のため (Spark 無料枠)。例外候補が出たら容量見積りとセットで判断する。確定済みの例外はセッション中 HR (`SessionRecord.hrBpmBySecond`、1 秒グリッドで数 KB〜十数 KB/セッション)。
