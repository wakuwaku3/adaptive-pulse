---
name: analyze
description: adaptive-pulse の Firestore に貯まった HIIT セッション履歴 (`users/{uid}/sessions/*`) と日次健康指標 (`users/{uid}/dailyMetrics/*`)、現在設定 (`users/{uid}/settings/current`) を会話の中で直接取得・集計・評価する。「今日のセッションを評価して」「今週の HR トレンドは?」「直近の resting HR の baseline」など、データ分析の依頼で発火する。
argument-hint: <分析内容を自由記述。例: "今日のセッションを評価して" / "直近 7 日の resting HR トレンド">
allowed-tools: [Bash, Read, Write]
---

# analyze skill

adaptive-pulse のデータ分析を「ユーザが手動で export して持ってくる」ステップ無しで会話内に閉じる。Firestore に直接アクセスし、必要なら可視化用の中間ファイルを `/tmp` に置いて結果だけテキストで返す。

## 0. 大前提

- gcloud は実行ホストに既に project owner として auth 済 (個人 Gmail) **である想定**。これは個人開発機での運用前提なので、別環境では使えない。最初に確認する。
- 個人健康データなので、抽出した中身を **repo にコミットしない / 第三者と共有しない** (`.claude/rules/secret.md`)。中間ファイルは `/tmp/` のみ。会話への出力も「数字の要約・トレンド・所見」までに留め、生の長大 dump を貼らない。

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
  }" > /tmp/sessions.json
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
  }" > /tmp/metrics.json
```

### 3-c. 現在設定

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://firestore.googleapis.com/v1/projects/adaptive-pulse-825a1/databases/(default)/documents/users/$U/settings/current" \
  > /tmp/settings.json
```

## 4. JSON の decode

ドキュメント本体は `fields.json.stringValue` に kotlinx-serialization した JSON 文字列が入っている。Python で 1 行で dict 化できる:

```python
import json
with open('/tmp/sessions.json') as f: entries = json.load(f)
sessions = [json.loads(e['document']['fields']['json']['stringValue'])
            for e in entries if 'document' in e]
```

schema 定義は repo 内の以下を読んで参照する:

- `core/src/main/kotlin/io/github/wakuwaku3/adaptivepulse/core/sync/SessionRecord.kt`
- `core/src/main/kotlin/io/github/wakuwaku3/adaptivepulse/core/sync/DailyHealthRecord.kt` (`SettingsDocument` も同居)

スキーマが変わったときは古いドキュメントに新フィールドが無いことがある → `dict.get(key)` で安全にアクセスする (`ignoreUnknownKeys = true` 相当)。

## 5. よく使う分析パターン

### 5-a. 1 セッションの評価
- `durationSec`, `cycles / plannedCycles`, `fatigueBrake`, `avgBpm`, `maxBpm`, `zoneRatio`, `calories`
- per-cycle `highDurationsSec` / `recoveryDurationsSec`:
  - **high は長いほど良い** (同負荷で upper 到達が遅い = 向上)
  - **recovery は短いほど良い** (回復が速い = 向上)
  - 後半サイクルで high 短縮 + recovery 延長 = その日の疲労蓄積シグナル
- `config` のスナップショットを見て、設定変更があった日とパフォーマンスを紐づける

### 5-b. resting HR の baseline と delta
- 過去 14 日の `restingHeartRateBpm` を median で baseline 化 (mean だと外れ値に弱い)
- 今日との delta が +5 bpm を超えたら recovery debt の警告候補

### 5-c. HRV (RMSSD) のトレンド
- 7 日 rolling mean を baseline に、当日 -20% 以下なら overtraining サイン
- 絶対値ではなく自分の baseline からの delta で評価する (個体差吸収)

### 5-d. 週次合計 in-zone time
- セッションごとに `sum(highDurationsSec)` を Z4-5 滞在時間として扱い、週で集計
- HIIT は週 30-60 分/Z4-5 で頭打ちが定説 → これを超え続けてる週は overtraining リスク

## 6. 出力の作法

- 数字は **表で要約**。原 JSON を会話に貼らない (健康データは秘匿、かつ読みづらい)。
- 個人 uid を会話に出さない。
- 所見は「数字 → 解釈 → 次に取れる行動」の三段で書く (今日のセッション評価セッションのスタイルを踏襲)。
- グラフが必要な場合は matplotlib で `/tmp/<topic>.png` に書き出して、その path をユーザに伝える (ユーザが必要なら開く)。Image は会話に貼らない。

## 7. エラーハンドリング

- 401/403: token 失効 or scope 不足 → `gcloud auth print-access-token` をやり直す。それでも駄目なら `gcloud auth login` をユーザに依頼。
- 期間内 0 件: structuredQuery は空 entry を 1 つ返す。`document` キーの有無で判定する (例: `[e for e in entries if 'document' in e]`)。
- ms と date の混同: sessions は ms、dailyMetrics は ISO date 文字列。フィルタ値の `integerValue` / `stringValue` を取り違えるとサイレントに 0 件になるので注意。
