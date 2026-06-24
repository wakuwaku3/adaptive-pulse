---
name: analyze
description: adaptive-pulse の Firestore に貯まった HIIT セッション履歴 (`users/{uid}/sessions/*`) と日次健康指標 (`users/{uid}/dailyMetrics/*`)、現在設定 (`users/{uid}/settings/current`) を会話の中で直接取得・集計・評価する。デフォルトは「セッション + dailyMetrics 全フィールド + 設定」を横断する総合分析。「今日のセッションを評価して」「今週の HR トレンドは?」「直近の resting HR の baseline」のような特定軸の依頼で発火することもある。
argument-hint: <分析内容を自由記述。空ならデフォルトで「直近 30 日の総合分析」を行う。例: "今日のセッションを評価して" / "直近 7 日の resting HR トレンド">
allowed-tools: [Bash, Read, Write]
---

# analyze skill

adaptive-pulse のデータ分析を「ユーザが手動で export して持ってくる」ステップ無しで会話内に閉じる。Firestore に直接アクセスし、中間ファイルと分析結果を repo 内の `tmp/analyze/{YYYY-MM-DD}/` (JST 暦日) に保存しつつ、会話には要約だけを返す。

## 0. 大前提

- gcloud は実行ホストに既に project owner として auth 済 (個人 Gmail) **である想定**。これは個人開発機での運用前提なので、別環境では使えない。最初に確認する。
- 個人健康データなので、抽出した中身を **repo にコミットしない / 第三者と共有しない** (`.claude/rules/secret.md`)。保存先の `tmp/` は `.gitignore` 済 + `.gitleaks.toml` allowlist 済。会話への出力も「数字の要約・トレンド・所見」までに留め、生の長大 dump を貼らない。

## 0-b. 出力先 (毎回必ず作る)

- 起動時に `mkdir -p tmp/analyze/$(TZ=Asia/Tokyo date +%F)` を実行し、その日のディレクトリを用意する。
- 中間 JSON / グラフ画像 / 分析サマリを全てこのディレクトリに保存する:
  - `sessions.json`, `metrics.json`, `settings.json` — Firestore からの raw response (上書き可)
  - `summary__{HHMM}__{slug}.md` — 会話で返した内容と同じ要約 (JST 時刻 + 分析テーマの slug)。1 回の分析で 1 ファイル。
  - `chart__{slug}.png` — 必要に応じて
- 会話最後にユーザに保存先パス (絶対パス) を 1 行で伝える。

## 1. 認証チェックと uid 解決

最初に毎回これを実行する (会話を跨ぐと token は揮発しているため)。

```bash
gcloud config get-value project 2>/dev/null  # → adaptive-pulse-825a1 を期待
gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null
TOKEN=$(gcloud auth print-access-token)
test -n "$TOKEN" || { echo "gcloud auth 未設定 — ユーザに gcloud auth login を依頼"; exit 1; }
```

`users` collection を 1 件だけ list して uid を取り出す (個人用途なのでユーザは 1 人だけ):

```bash
U=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://firestore.googleapis.com/v1/projects/adaptive-pulse-825a1/databases/(default)/documents/users?pageSize=1" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['documents'][0]['name'].split('/')[-1])")
echo "uid=$U"
```

uid を会話に貼る必要はない (個人識別子のため不要露出を避ける)。以降の curl で `$U` を使う。

## 2. 期間フィルタの組み立て

セッションは `startedAtMs` (Long, ms)、dailyMetrics は `date` (`YYYY-MM-DD`) で並ぶ。JST 暦日で扱うのが普通なので、Python で境界を作る:

```bash
python3 <<'PY'
from datetime import datetime, timezone, timedelta
JST = timezone(timedelta(hours=9))
today = datetime.now(JST).replace(hour=0, minute=0, second=0, microsecond=0)
print('today_start_ms=', int(today.timestamp()*1000))
print('today_end_ms=  ', int((today+timedelta(days=1)).timestamp()*1000))
print('today_date=    ', today.strftime('%Y-%m-%d'))
PY
```

## 3. クエリレシピ

### 3-a. 期間内のセッション (structuredQuery + :runQuery)

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X POST "https://firestore.googleapis.com/v1/projects/adaptive-pulse-825a1/databases/(default)/documents/users/$U:runQuery" \
  -d "{
    \"structuredQuery\": {
      \"from\": [{\"collectionId\": \"sessions\"}],
      \"where\": {\"compositeFilter\": {\"op\": \"AND\", \"filters\": [
        {\"fieldFilter\": {\"field\": {\"fieldPath\": \"startedAtMs\"}, \"op\": \"GREATER_THAN_OR_EQUAL\", \"value\": {\"integerValue\": \"$START_MS\"}}},
        {\"fieldFilter\": {\"field\": {\"fieldPath\": \"startedAtMs\"}, \"op\": \"LESS_THAN\",          \"value\": {\"integerValue\": \"$END_MS\"}}}
      ]}},
      \"orderBy\": [{\"field\": {\"fieldPath\": \"startedAtMs\"}, \"direction\": \"ASCENDING\"}]
    }
  }" > tmp/analyze/$(TZ=Asia/Tokyo date +%F)/sessions.json
```

`:runQuery` の戻りは「entries の配列」で、各要素に `document` フィールドが入る。空ヒット時は `[{}]` (空 entry) が返るので `entry.get('document')` で判定する。

### 3-b. 日付範囲の dailyMetrics

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X POST "https://firestore.googleapis.com/v1/projects/adaptive-pulse-825a1/databases/(default)/documents/users/$U:runQuery" \
  -d "{
    \"structuredQuery\": {
      \"from\": [{\"collectionId\": \"dailyMetrics\"}],
      \"where\": {\"compositeFilter\": {\"op\": \"AND\", \"filters\": [
        {\"fieldFilter\": {\"field\": {\"fieldPath\": \"date\"}, \"op\": \"GREATER_THAN_OR_EQUAL\", \"value\": {\"stringValue\": \"$FROM_DATE\"}}},
        {\"fieldFilter\": {\"field\": {\"fieldPath\": \"date\"}, \"op\": \"LESS_THAN_OR_EQUAL\", \"value\": {\"stringValue\": \"$TO_DATE\"}}}
      ]}},
      \"orderBy\": [{\"field\": {\"fieldPath\": \"date\"}, \"direction\": \"ASCENDING\"}]
    }
  }" > tmp/analyze/$(TZ=Asia/Tokyo date +%F)/metrics.json
```

### 3-c. 現在設定

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://firestore.googleapis.com/v1/projects/adaptive-pulse-825a1/databases/(default)/documents/users/$U/settings/current" \
  > tmp/analyze/$(TZ=Asia/Tokyo date +%F)/settings.json
```

## 4. JSON の decode

ドキュメント本体は `fields.json.stringValue` に kotlinx-serialization した JSON 文字列が入っている。Python で 1 行で dict 化できる:

```python
import json
with open('tmp/analyze/<today>/sessions.json') as f: entries = json.load(f)
sessions = [json.loads(e['document']['fields']['json']['stringValue'])
            for e in entries if 'document' in e]
```

schema 定義は repo 内の以下を読んで参照する:

- `core/src/main/kotlin/io/github/wakuwaku3/adaptivepulse/core/sync/SessionRecord.kt`
- `core/src/main/kotlin/io/github/wakuwaku3/adaptivepulse/core/sync/DailyHealthRecord.kt` (`SettingsDocument` も同居)

スキーマが変わったときは古いドキュメントに新フィールドが無いことがある → `dict.get(key)` で安全にアクセスする (`ignoreUnknownKeys = true` 相当)。

## 5. デフォルト分析モード — 総合分析 (これを最初にやる)

ユーザが特定軸を指定しなかったら、必ず以下の順で「セッション × dailyMetrics × 設定」を横断的に見る。「セッションだけ」に矮小化しない。

### 5-0. 取得状況の検査 (必ず最初)

dailyMetrics は欠損が多い前提で、まず以下を検出して所見に含める。これ自体が一次課題のことがある。

- **field coverage**: `DailyHealthRecord` の各フィールドが「過去 N 日のうち何日 non-null か」を集計する。0/N または極端に低いフィールドは「未取得 = システム未連携」の可能性として明示する。
- **sentinel 欠損**: `bodyFatPct = 0.0` は実測ではなく欠損埋め。`weightKg`/`leanBodyMassKg` の 0 も同じ。集計時は除外する。
- **完全空白期間**: 特定日付以降「全フィールドが null」になっているなら、Health Connect → app の取り込みパイプ停止を疑う (Firestore 書き込み自体は生きていてもこれは起きる)。
- **整合性異常**: `exerciseExtraKcal` が常に 0 なのに同日 HIIT セッションがある → TDEE 集計のバグ。`totalCaloriesKcal` だけあって `tdeeKcal` が無い、なども併せて見る。

### 5-1. 取れている範囲での横断トレンド (必ず触れる項目)

| 軸 | 主フィールド | 見方 |
|----|--------------|------|
| 体組成 | `weightKg`, `bodyFatPct`, `leanBodyMassKg` | sentinel 除外。週次平均で減少/増加トレンドと bounce 日を識別 |
| 食事 | `intakeKcal`, `proteinG`, `fatG`, `carbsG` | sample 日数が少なくても代表値として提示。極端な日 (cheat day) を identify |
| 活動 | `steps`, `distanceMeters`, `floorsClimbed`, `tdeeKcal`, `exerciseExtraKcal` | 在宅/出勤での bimodal を許容。TDEE は HIIT 加算が反映されているか確認 |
| 心拍/睡眠 (Pixel Watch) | `restingHeartRateBpm`, `hrvRmssdMs`, `sleepDurationMin`, `sleepDeepMin`, `sleepRemMin`, `spo2AvgPct`, `respiratoryRateAvg`, `skinTemperatureDeltaC` | これらが取れているならセッション良否との対応を見る。1 つも取れていないなら 5-0 で「未連携」として上に出す |
| バイタル変動 | rHR delta, HRV delta | 7-14d median を baseline に、当日 delta で recovery debt を推定 (取れている日に限る) |
| HIIT セッション | `highDurationsSec`, `recoveryDurationsSec`, `fatigueBrake`, `cycles/plannedCycles`, `config` snapshot | high 長期化 / recovery 短期化が向上。設定変更日とパフォーマンス変化を紐づける |

### 5-2. セッション × 同日 metrics の交差

セッションがある日に対し、同日の `weightKg / bodyFatPct / steps / intakeKcal / proteinG / tdeeKcal / restingHeartRateBpm / hrvRmssdMs / sleepDurationMin` を 1 行ずつ並べた表を作る。これで「データが取れている日のセッションと体組成/栄養/睡眠の関係」が一目で見える。空白だらけになるなら、そこからシステム側の課題を読み取って所見に書く。

### 5-3. 設定スナップショットの変遷

セッションごとの `config` snapshot を時系列に並べ、`upperBpm / lowerBpm / targetCycles / fatigueRatio / targetCadenceHigh / targetCadenceRecovery` の変化点を抽出する。「ある日から brake が増えた」は設定変更が背景にあることが多いので必ず確認する。

### 5-4. 総合判断と次の行動

最後に「観点 (データ収集 / 体組成 / 食事 / 活動 / 睡眠 / HIIT / Watch 連携) ごとに、状態 → 行動」を表でまとめる。データ収集の不全がボトルネックなら、それを最優先行動として明示する。

## 6. 深掘り用の追加レシピ (ユーザが特定軸を指定したとき)

総合分析の中の特定軸を深掘りしたいときに使う。デフォルトでは 5 の総合分析を完走してから入る。

### 6-a. 1 セッションの評価
- `durationSec`, `cycles / plannedCycles`, `fatigueBrake`, `avgBpm`, `maxBpm`, `zoneRatio`, `calories`
- per-cycle `highDurationsSec` / `recoveryDurationsSec`:
  - **high は長いほど良い** (同負荷で upper 到達が遅い = 向上)
  - **recovery は短いほど良い** (回復が速い = 向上)
  - 後半サイクルで high 短縮 + recovery 延長 = その日の疲労蓄積シグナル
- `config` のスナップショットを見て、設定変更があった日とパフォーマンスを紐づける

### 6-b. resting HR の baseline と delta
- 過去 14 日の `restingHeartRateBpm` を median で baseline 化 (mean だと外れ値に弱い)
- 今日との delta が +5 bpm を超えたら recovery debt の警告候補

### 6-c. HRV (RMSSD) のトレンド
- 7 日 rolling mean を baseline に、当日 -20% 以下なら overtraining サイン
- 絶対値ではなく自分の baseline からの delta で評価する (個体差吸収)

### 6-d. 週次合計 in-zone time
- セッションごとに `sum(highDurationsSec)` を Z4-5 滞在時間として扱い、週で集計
- HIIT は週 30-60 分/Z4-5 で頭打ちが定説 → これを超え続けてる週は overtraining リスク

## 7. 出力の作法

- 数字は **表で要約**。原 JSON を会話に貼らない (健康データは秘匿、かつ読みづらい)。
- 個人 uid を会話に出さない。
- 所見は「数字 → 解釈 → 次に取れる行動」の三段で書く。
- **グラフはデフォルト生成しない**。アプリ側の dashboard で同じ可視化ができるため重複。ユーザが「グラフが欲しい」と明示したときだけ matplotlib で `tmp/analyze/{today}/chart__<slug>.png` に書き出し、その path (絶対) をユーザに伝える (matplotlib が未導入の環境では「未導入なのでスキップ」と一言だけ返す)。Image は会話に貼らない。
- 分析サマリは会話に返すと同時に `tmp/analyze/{today}/summary__{HHMM}__{slug}.md` に保存する (会話と repo 内の履歴で内容が一致するように)。

## 8. エラーハンドリング

- 401/403: token 失効 or scope 不足 → `gcloud auth print-access-token` をやり直す。それでも駄目なら `gcloud auth login` をユーザに依頼。
- 期間内 0 件: structuredQuery は空 entry を 1 つ返す。`document` キーの有無で判定する (例: `[e for e in entries if 'document' in e]`)。
- ms と date の混同: sessions は ms、dailyMetrics は ISO date 文字列。フィルタ値の `integerValue` / `stringValue` を取り違えるとサイレントに 0 件になるので注意。
