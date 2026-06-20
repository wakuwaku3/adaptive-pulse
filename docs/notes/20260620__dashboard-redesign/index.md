# Google Health 代替ダッシュボードの設計

## 動機

Google Health の "消費カロリー" 表示が watch ソース (4876) を生で出していて、内部の `TotalCaloriesBurnedRecord` 集約 (2991) や Fit ソース (2024) と桁違いに乖離していた。同じユーザの同じ日のデータでソースごとに 2〜3 倍の差が出るため、Google Health のダッシュボードを deficit / トレンド分析の根拠にできない。

加えて、毎日の運動・食事・睡眠の状況を「アプリを開いた瞬間に」把握したい運用要件があり、現状の「日次 06:00 Firestore upload + share intent でエクスポート」フローは reactive すぎる。

## ゴール

- Health Connect から取れる全指標を **phone アプリ内でダッシュボード表示**する。Google Health UI に頼らない
- データソース別の breakdown を出して **watch 過大評価を見える化**する
- Asken に食事を入れた数十分以内に **アプリを開けば反映**される同期間隔
- 内臓脂肪減量フェーズの主要指標 (体重 / 体脂肪 / TDEE / intake / deficit / 睡眠 / HRV / RHR / PFC) を一画面で把握できる

## 主要決定

### データ層を 2 系統に分離

| 層 | 用途 | 保存先 | 寿命 |
|---|---|---|---|
| **日次集約** (`DailyHealthRecord`) | 端末横断の分析・履歴 | Firestore + Room キャッシュ | 永続 (Spark 無料枠で十分) |
| **ダッシュボード詳細** (時系列・ソース別 breakdown) | 当画面表示専用 | **phone local Room のみ** | HC が原本、機種変時は再同期 |

時系列は Room のサイズが膨らみやすいので Firestore には上げない。HC 自体が時系列の正本なので、機種変時にローカルキャッシュが消えても再同期で復元できる前提。

### 同期間隔と契機

- **Periodic WorkManager 1h** (バックグラウンド): "今日 + 過去 7 日" を Room に upsert
- **ProcessLifecycleOwner ON_START** (アプリ前景化): 即時同期 1 回
- **Pull-to-refresh** (UI): 手動トリガー

初回同期だけは別経路:

- **インストール直後の Initial sync**: 過去 5 年 (1825 日) を一括取り込み
  - HC `READ_HEALTH_DATA_HISTORY` permission (Android 14+) を要求。拒否時は HC 既定の 30 日にフォールバック
  - DataStore に `initialSyncCompletedAtMs` を持って 2 回目以降スキップ
  - 日次集約のみ。HR / SpO2 等の時系列は対象外 (容量爆発を避ける)
- 時系列レコード (HeartRate / Vitals サンプル) は通常同期・初回同期問わず **「今日 + 昨日」固定**

### スコープに含める HC レコード

| カテゴリ | レコード型 | 既存 | 新規 |
|---|---|---|---|
| 心拍 | `HeartRateRecord` (samples), `RestingHeartRateRecord`, `HeartRateVariabilityRmssdRecord` | ✓ | 時系列読み出しを新規 |
| 睡眠 | `SleepSessionRecord` (stages) | ✓ | |
| 活動 (集約) | `StepsRecord`, `ActiveCaloriesBurnedRecord`, `TotalCaloriesBurnedRecord` | ✓ | |
| 活動 (追加) | `DistanceRecord`, `FloorsClimbedRecord`, `ElevationGainedRecord`, `ExerciseSessionRecord` | | ✓ |
| 体組成 | `WeightRecord`, `BodyFatRecord`, `LeanBodyMassRecord`, `HeightRecord` | ✓ | |
| 食事 | `NutritionRecord` (energy / protein / fat / carbs) | ✓ | fiber / sugar / sodium 等の細目を追加 |
| バイタル | `OxygenSaturationRecord`, `RespiratoryRateRecord`, `SkinTemperatureRecord` | | ✓ |
| 基礎代謝 | `BasalMetabolicRateRecord` | | ✓ (参考値) |

### データソース別 breakdown

`HealthConnectClient.aggregate` は dataOrigin 別の breakdown を返さないため、レコードを `readRecords` で取って `Metadata.dataOrigin.packageName` 別に集計する関数を `HealthDataSource` に追加する。

主要指標 (steps / calories / sleep) は集約と breakdown の両方を保持する。UI では集約値を主、長押し or 詳細画面で内訳。

### UI 配置 (`.claude/rules/ui.md` 準拠)

主画面は HISTORY のまま。HISTORY 画面の上部に Today カード + 7-day trend を統合し、各カテゴリ別の詳細サブ画面は overflow menu からアクセスする。

```
HISTORY (主画面)
├─ Today カード (集約値)
├─ 7-day trend (deficit / 体重)
└─ Sessions 一覧 (既存)

⋮ overflow menu
├─ Heart Rate detail
├─ Sleep detail
├─ Calories detail (ソース別)
├─ Nutrition detail
├─ Vitals detail
├─ Settings (既存)
├─ Export 30 days (既存)
└─ Sign out (既存)
```

タブは使わない (主画面は 1 枚)。

## 悩んだ点と判断

### "リアルタイム" 同期の本当の要求

ユーザは初期に "リアルタイム同期" と言ったが、深掘りすると「Asken 入力 → 数分後にアプリを開いて反映されていればいい」が実態。HR の真のリアルタイムは watch で既に見える。Periodic 1h + 前景化即時で「開いた瞬間最新」を満たすので採用。

### 全レコード型を取りに行くか、ゴールドリスト方式か

全レコード型を強欲に取りに行くと権限要求のダイアログが長くなり、ユーザの心理的ハードルが上がる。一方で「あとから追加権限を要求」は HC の UX 上厄介。ゴール (内臓脂肪減量 + 運用品質) に直結する指標を選ぶ。Pixel Watch + 体組成計 + Asken が書いているはずのレコード型に限定する。

### Firestore に時系列も上げるか

選択肢: (a) 全部 Firestore、(b) 日次集約のみ Firestore、(c) Firestore はやめる。

判断: (b)。理由は (i) 時系列は HC が正本なので機種変時に Firestore に依存しない、(ii) Spark プランの 20K writes/日 制限が時系列で消費される、(iii) 1 ドキュメント 1MB 制限に時系列が当たる可能性。日次集約だけを Firestore に上げて履歴正本にする現状方針を維持。

### 5 年遡及 vs 1 年遡及

ユーザ希望は 5 年。HC 側にデータがあれば取れる (なければ null)。日次集約 1 レコード ≒ 数百バイトなので 1825 行 × 数百 B = ~1MB 程度で Room も Firestore も余裕。1 年だと「コロナ前との比較」等の長期可視化ができないので 5 年に倒す。

### Initial sync の進捗 UX

5 年同期は HC のレスポンス次第で数十秒〜数分かかる。アプリ起動時にブロッキングするとサインインフローが詰まるので、バックグラウンドで走らせ、Today カードに "Initial sync (x/1825 days)" の薄い進捗バーを表示する。完了後は普通のダッシュボードに切り替わる。

### 既存 `HealthIngestWorker` の扱い

選択肢: (a) `HealthSyncWorker` に置き換える、(b) 並存、(c) 拡張する。

判断: (c)。既存の `HealthIngestWorker` は「Firestore に日次集約を upload」の役割が明確で、これは新設計でも残る (Stream A)。Periodic 1h で走らせる新しい Stream B 用 worker は別クラスとして並走させる。前者は日次 06:00 のままで churn を抑え、後者は 1h でダッシュボード鮮度を保つ。

→ さらに整理: `HealthIngestWorker` は「HC → Room → Firestore (日次集約)」の責務に変え、`HealthSyncWorker` (新規) は「HC → Room (フル)」を担う。Firestore upload は Room 更新後に派生する。

### Background sync 間隔

選択肢: 15 / 30 / 60 / 120 分。

判断: 60 分。Doze mode で実際は 1.5〜2h 程度に伸びる前提でも、開いた瞬間に foreground 同期が走るので体感は問題ない。15-30 分は battery への影響が読みづらく、120 分はモニタリング目的だと荒い。

## 段取り

実装は 1 連の作業として進める (PR 分割は重荷)。コミットは以下の粒度で:

1. **flow doc + 依存追加** (このコミット): Room, Lifecycle-process の `libs.versions.toml` 追加、`mobile/build.gradle.kts` 追加、AndroidManifest の HC 追加権限
2. **データ層**: Room schema/DAO/Entities、`HealthDataSource` 拡張 (新レコード型・dataOrigin 別 read)、`HealthSyncWorker` 新設、`HealthIngestWorker` リファクタ
3. **UI**: HISTORY の Today カード + 7-day trend 統合、詳細サブ画面群 (HR / Sleep / Calories / Nutrition / Vitals)

## 後続の余地

- Asken 連携の食事細目 (糖質 / 食物繊維 / ナトリウム) を deficit ダッシュボードに統合
- HRV / 睡眠スコアからの自動 deload 判定 (HIIT セッションの強度を自動調整)
- watch tile でその日の deficit を直接見られるようにする
- Room に Insights テーブルを足し、計算結果 (Mifflin-St Jeor BMR / kcal-per-step 推定) をキャッシュ
