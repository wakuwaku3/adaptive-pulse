# Health Connect → ダッシュボード + エクスポート

phone アプリ (`:mobile`) が Health Connect から日次の健康指標と時系列を取り込み、
端末ローカル Room キャッシュとアプリ内ダッシュボードに反映する。同時に日次集約は
Firestore に upsert して機種変対応の正本を保つ。手動の JSON エクスポートは
external (Claude 等) への取り出し口として残す。

## 入手するデータ

ダッシュボード表示の主役は `DailyHealthRecord` (1 日 1 行)。Pixel Watch / 体組成計 /
Asken など、端末側で Health Connect に連携済みのアプリが書く範囲をカバーする。

| カテゴリ | フィールド | 単位 | データ源 (例) |
|---|---|---|---|
| 心拍 | `restingHeartRateBpm` / `hrvRmssdMs` / `avgHeartRateBpm` / `minHeartRateBpm` / `maxHeartRateBpm` | bpm / ms | Pixel Watch |
| 睡眠 | `sleepDurationMin` / `sleep{Deep, Rem, Light, Awake}Min` | 分 | Pixel Watch |
| 活動 | `steps` / `distanceMeters` / `floorsClimbed` / `elevationGainedMeters` | count / m | Pixel Watch / Phone |
| カロリー | `activeCaloriesKcal` / `totalCaloriesKcal` / `basalCaloriesKcal` | kcal | Pixel Watch (Fitbit) / Phone |
| TDEE | `tdeeKcal` / `exerciseExtraKcal` | kcal | 本アプリが `core.calories.TdeeCalc` で再計算 |
| 体組成 | `weightKg` / `bodyFatPct` / `leanBodyMassKg` / `heightCm` | kg / % / cm | スマート体重計 |
| 食事 | `intakeKcal` / `proteinG` / `fatG` / `carbsG` / `fiberG` / `sugarG` / `sodiumMg` | kcal / g / mg | Asken 等 |
| バイタル | `spo2AvgPct` / `spo2MinPct` / `respiratoryRateAvg` / `skinTemperatureDeltaC` | % / /min / °C | Pixel Watch |

欠損は null で残し、推定で埋めない。

### データソース別 breakdown

主要指標 (TDEE / 活動カロリー / 歩数 / 距離 / フロア / 摂取カロリー / 基礎代謝)
は **どのデータソースが書いた値か** を別途保持する。watch / phone / Fit がそれぞれ
独立に計算した値を並べることで、Google Health UI で見える「watch だけ過大評価」
のような乖離を機械的に確認できる。

`HealthConnectClient.aggregate` は dataOrigin 別 breakdown を返さないので、
`readRecords` で生レコードを取って `Metadata.dataOrigin.packageName` 別に集計し直す。

### 時系列

容量制御のため `HeartRateRecord` の生サンプル (5 分粒度) と SpO2 / 呼吸数 / 皮膚温は
**今日 + 昨日のみ** をローカル Room に保持する。詳細サブ画面でグラフ表示する。

## 権限

`mobile/AndroidManifest.xml` で `android.permission.health.READ_*` を宣言し、
runtime grant は HC の `PermissionController.createRequestPermissionResultContract()`
で取る (普通の Android runtime permission とは別経路)。

主要な権限:

- 心拍 / 睡眠 / 歩数 / カロリー / 体組成 / 栄養
- 距離 / 階段 / 上昇高度 / 運動セッション
- SpO2 / 呼吸数 / 皮膚温 / 基礎代謝
- `READ_HEALTH_DATA_HISTORY` (Android 14+): 過去 30 日より前のデータ取得に必要。
  許可されれば初回 5 年同期が回る。拒否時は HC が 30 日で打ち切る。
  runtime 要求は `HealthDataSource.REQUEST_PERMISSIONS` に含めて行うが、接続判定
  (`PERMISSIONS` の containsAll) には含めない: 拒否されても直近 30 日の通常同期は
  成立させる。未付与のまま接続済みの端末には起動時に権限 sheet を再提示し、付与された
  瞬間に強制 backfill (`enqueueInitialSync`) を仕掛けて 30 日制限時代の空マーカー日を
  埋め直す。

取得 UI / revoke 経路の運用は従来通り (Settings 画面のトグルで一括 grant、個別
revoke は Android Settings → Health Connect)。

## ローカル Room キャッシュ

ダッシュボード表示用のフルキャッシュ。テーブル:

| テーブル | 役割 | 保持期間 |
|---|---|---|
| `daily_snapshot` | 日次集約 (`DailyHealthRecord` 同等) | 過去 5 年 (初回 sync) + 通常 sync で today + 7 日 |
| `metric_by_source` | 主要指標 × dataOrigin の breakdown | 通常 sync 範囲と同期 |
| `heart_rate_sample` | HR 時系列 (5 分粒度) | 今日 + 昨日 |
| `vital_sample` | SpO2 / 呼吸数 / 皮膚温の時系列 | 今日 + 昨日 |
| `exercise_session` | 他アプリの運動セッション | 通常 sync 範囲と同期 |

機種変時はローカルキャッシュが消えるが、HC が原本なので再同期で復元できる
(Firestore に上げる必要のあるのは「機種をまたいでも消えてほしくない」日次集約のみ)。

## 同期パイプライン

3 系統が同じ `HealthSyncWorker` を呼ぶ:

| トリガー | 範囲 | 用途 |
|---|---|---|
| Periodic 1h (WorkManager) | today + 過去 7 日 | バックグラウンドで「久々に開いた時の遅延」を抑える |
| ProcessLifecycleOwner `ON_START` | today + 過去 7 日 | アプリ前景化時に Asken の最新入力を反映する |
| pull-to-refresh / HC 連携初回 | today + 過去 7 日 | ユーザ操作 |
| 初回限定 `InitialSyncWorker` | today + 過去 5 年 (1825 日) | インストール後 1 回だけ。`READ_HEALTH_DATA_HISTORY` 拒否時は HC 既定の 30 日に縮退 |

初回完了判定は Room の最古日から導く (`DashboardSyncManager.enqueueInitialSyncIfNeeded`)。
Room destructive migration で wipe されたケースは自動で再 backfill される。履歴権限が
後から付与されたときは判定を経由せず強制 backfill する (空マーカー行を「同期済み」と
誤認するため)。

HC 読み込みはローカルなので network 不要だが、Firestore upsert を伴うため
WorkManager の constraint は `NetworkType.CONNECTED` を要求する (network 不在時は
OS が待機させる)。

Worker は HC 権限が無ければ `Result.success()` で no-op に倒れる。`ExistingPeriodicWorkPolicy.KEEP`
で複数回 enqueue しても置き換わらない。前景化時の one-time も `ExistingWorkPolicy.KEEP` で
重複しない。

## Firestore 連携

- `users/{uid}/dailyMetrics/{YYYY-MM-DD}` に日次集約を upsert (Stream A)。
- ドキュメント本体は `DailyHealthRecord` の JSON 文字列を `json` フィールドに持ち、
  doc id を日付固定にすることで重複 ingest が単純上書きになる (冪等)。
- `DailyHealthRecord` には dataOrigin 別 breakdown (`breakdown`) と他アプリの運動
  セッション (`externalSessions`) も同梱する。HC には端末を横断してアクセスできない
  ため、時系列を除く読み取り済みデータはすべて Firestore まで届けて分析可能にする
  (`.claude/rules/health-connect-sync.md`)。
- 時系列 (HR / Vital サンプル列) は容量のため Firestore に上げない (端末ローカルで完結)。
- Spark プランの 20K writes/日 制限: 通常 sync で 7 日 × 1 ドキュメント = 7 writes/h、
  初回 5 年 sync で 1825 writes (1 回のみ) なので余裕。

## ダッシュボード UI

主画面 (HISTORY) 上部に Today カード (集約値) + 7-day trend (deficit / 体重) を統合。
overflow menu から HR / Sleep / Calories / Nutrition / Vitals の詳細サブ画面を開く。

### TDEE は自前計算

HC `TotalCaloriesBurnedRecord` は dataOrigin 横断で sum されるため、watch / Fitbit が
書く過大評価 (実測比 2-3x) を吸ってしまう。dashboard 表示と deficit 算出には自前
計算した TDEE を使う:

```
TDEE = BMR + steps × (weight × 0.0005) + Σ ExerciseExtra
where:
  BMR          = basalCaloriesKcal (HC) ?: Mifflin-St Jeor(weight, height, age)
  ExerciseExtra = (MET - 1) × weight × hours
    対象 session: 自社 HIIT (`SessionRecord`、MET 8.0) + HC `ExerciseSessionRecord` (type 別 MET 表)
    歩数で吸収できる type (WALKING / RUNNING) は extra から除外
    自社 HIIT と時間重複した HC session は二重計上回避で skip
  自社 HIIT は SessionRecord 直読みで TDEE に入る (HC への書き戻し経路には依存しない)
```

計算は `core.calories.TdeeCalc` (純 Kotlin、JVM テスト) で行い、結果を
`DailyHealthRecord.tdeeKcal` / `exerciseExtraKcal` に詰めて Room + Firestore に永続化する。
Today カードでは TDEE 大文字 + 「+exercise <kcal>」のサブラインで内訳を見せる。

### 本アプリを HC のカロリー master にする

自前 TDEE を `TotalCaloriesBurnedRecord` として HC に書き戻し、他アプリ
(Google Fit / MyFitnessPal 等) からもこの値を読めるようにする
(`HealthDataSource.writeDailyTotalCalories`):

- 1 日 1 レコード。range = 当日 00:00 〜 24:00 (今日は now で頭打ち)
- 値 = `DailyHealthRecord.tdeeKcal` (= `TdeeCalc.compute` の結果)
- `clientRecordId = "ap-total-{YYYY-MM-DD}"` で冪等。同期窓 (8 日) を毎回回しても積み上がらない
- 書き出しは `HealthSyncWorker` の日次同期完了後にまとめて実行する

権限は `WRITE_TOTAL_CALORIES_BURNED`。

HIIT セッションや active カロリーは HC に書き出さない。Pixel Watch (Fitbit) 側の
自動検出が `ExerciseSessionRecord` を書く運用に揃え、外部消費者は HIIT を「Workout」
粒度で見ることになる (HIIT 型情報は本アプリ内に閉じる)。

HC には「他アプリの書き込みを禁止する」primitive が無い。`TotalCaloriesBurnedRecord`
の aggregate は dataOrigin 横断 SUM なので、Fitbit が同じ日に Total を書き続ける
限り**外部 aggregate 読みは 2 重計上される**。完全に master にするには
ユーザが HC 設定で Fitbit / Connected Fitness の「全カロリー」書き込み権限を手動
revoke する必要がある。`dataOrigin` で filter する読み手 (Google Fit 等) は
本アプリ値だけが見える。

## エクスポート経路 (Claude 等への手動取り出し)

phone overflow メニューの「Export 30 days」から実行する。

1. `HealthDataExporter` が直近 30 日の `DailyHealthRecord` を HC から組み立てる
2. 同じ期間の `SessionRecord` を Firestore + ローカル pending から集める
3. 両者を `HealthDataExport` の 1 オブジェクトに包んで JSON 化
4. アプリの `cacheDir/exports/adaptivepulse-YYYY-MM-DD_YYYY-MM-DD.json` に書き出し
5. `FileProvider` で URI 化して `ACTION_SEND` (chooser 経由) で他アプリに渡す

書き出した JSON は端末ローカルのキャッシュなので、ユーザが選んだ送信先アプリ
(Gmail / Drive / Slack / Keep / clipboard manager 等) に乗せて初めて永続化される。

## 将来の拡張

- **Claude MCP connector**: Firestore 配下の `dailyMetrics` + `sessions` をそのまま
  読ませる MCP server を立てる。手動 JSON エクスポートは「困った時の救命ボート」
  として残す。
- **watch tile**: その日の deficit を watch face から直接見られるようにする。
- **deload 自動判定**: HRV / RHR / 睡眠から HIIT 強度の自動調整。
