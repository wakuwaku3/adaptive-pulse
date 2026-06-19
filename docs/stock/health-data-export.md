# Health Connect → JSON エクスポート

phone アプリ (`:mobile`) が Health Connect から日次の健康指標を集めて 1 つの JSON
にまとめ、標準の Share Intent で外部に渡せるようにする。analysis (まずは Claude
への手動投入、将来は MCP connector) の入り口を作るのが目的。

## 入手するデータ

`DailyHealthRecord` に 1 日 1 行で詰める。データ源は端末側で Health Connect に
連携済みのアプリ:

| フィールド | 単位 | データ源 (例) |
|---|---|---|
| `restingHeartRateBpm` | bpm | Pixel Watch |
| `hrvRmssdMs` | ms | Pixel Watch (主に睡眠中) |
| `avgHeartRateBpm` | bpm | Pixel Watch (当日全 HR サンプルの平均) |
| `sleepDurationMin` / `sleep{Deep, Rem, Light, Awake}Min` | 分 | Pixel Watch |
| `steps` | count | Pixel Watch |
| `activeCaloriesKcal` / `totalCaloriesKcal` | kcal | Pixel Watch |
| `weightKg` / `bodyFatPct` / `leanBodyMassKg` | kg / % / kg | スマート体重計 |
| `heightCm` | cm | あすけん 等 (静的プロフィール) |
| `intakeKcal` / `proteinG` / `fatG` / `carbsG` | kcal / g | 食事ログアプリ (あすけん 等) |

欠損は null で残し、推定で埋めない。

## 権限

`mobile/AndroidManifest.xml` で `android.permission.health.READ_*` を宣言し、
runtime grant は HC の `PermissionController.createRequestPermissionResultContract()`
で取る (普通の Android runtime permission とは別経路)。

- 取得 UI: phone の Settings 画面に「Health Connect」トグル。ON で権限ダイアログ起動、
  全権限 grant されたら接続済み表示に切り替わる。一部 grant でも export 自体は動く
  (取れた項目だけ埋まる)。
- 個別 revoke は Android Settings → Health Connect から行う (in-app では一括 revoke
  API が無いため案内のみ)。
- HC の権限ダイアログから「アプリの説明」リンクで戻る経路として `MainActivity` が
  `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` を受ける。

## バックグラウンド取り込み

権限を初めて grant した瞬間に、`HealthIngestWorker` 経由で 2 種類の WorkManager
ジョブを仕込む:

- **初回 back-fill** (`OneTimeWorkRequest`, unique work `health-connect-backfill`):
  過去 90 日分の `DailyHealthRecord` を Health Connect から読み、Firestore
  `users/{uid}/dailyMetrics/{YYYY-MM-DD}` に upsert する。doc id を日付固定にして
  いるため重複 ingest しても上書きで整合する (冪等)。`ExistingWorkPolicy.REPLACE`
  なので「再 grant で過去データを取り直したい」ときも単純にトグル off → on で再走できる。
- **日次同期** (`PeriodicWorkRequest`, unique work `health-connect-daily`, interval 1 day):
  毎日 06:00 (初回は次の朝) に「昨日 + 一昨日」の 2 日分を冪等 upsert する。
  `ExistingPeriodicWorkPolicy.KEEP` で複数回 schedule しても置き換わらない。
  アプリ起動時にも `scheduleDaily` を呼んで、未スケジュール状態 (再インストール直後など)
  から復帰できるようにしている。

ジョブの constraint は `NetworkType.CONNECTED` のみ (Firestore 書き込みに必要)。
それ以外の制約 (充電中・wifi 限定 etc.) は付けない。

**アプリ起動状態への依存**: WorkManager は OS の JobScheduler に乗るのでアプリが
killed されていても OS が条件を満たした時に走る。例外は次のいずれかに該当する間:

- ユーザが Settings → Apps → Force stop した直後 (一度 phone を開けば復帰)
- Battery Optimization で対象アプリを「制限」に倒した場合 (推奨しない設定)
- HC の権限が revoke された場合 (worker は `Result.success()` で no-op に倒れる)

OFF トグルで両ジョブを cancel する。Firestore に書かれたデータは消さない (履歴は残す)。

## エクスポート経路

phone overflow メニューの「Export 30 days」から実行する。

1. `HealthDataExporter` が直近 30 日の `DailyHealthRecord` を HC から組み立てる
2. 同じ期間の `SessionRecord` を Firestore + ローカル pending から集める
3. 両者を `HealthDataExport` の 1 オブジェクトに包んで JSON 化
4. アプリの `cacheDir/exports/adaptivepulse-YYYY-MM-DD_YYYY-MM-DD.json` に書き出し
5. `FileProvider` で URI 化して `ACTION_SEND` (chooser 経由) で他アプリに渡す

書き出した JSON は端末ローカルのキャッシュなので、ユーザが選んだ送信先アプリ
(Gmail / Drive / Slack / Keep / clipboard manager 等) に乗せて初めて永続化される。

## 将来の拡張

- **HC → Firestore daily 同期**: WorkManager で 1 日 1 回前日分を `users/{uid}/dailyMetrics/{YYYY-MM-DD}`
  に upsert する。複数端末・端末紛失でも履歴が消えなくなる。Rules を `dailyMetrics` 用に
  拡張する必要あり。
- **Claude MCP connector**: Firestore 配下の `dailyMetrics` + `sessions` をそのまま
  読ませる MCP server を立てる (Cloud Functions / Cloud Run のどちらか)。手動 JSON
  エクスポートは将来も「困った時の救命ボート」として残す。
