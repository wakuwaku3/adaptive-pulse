---
name: analyze
description: adaptive-pulse の Firestore に貯まった HIIT セッション履歴 (`users/{uid}/sessions/*`)、筋トレ記録 (`users/{uid}/workouts/*`)、日次健康指標 (`users/{uid}/dailyMetrics/*`)、現行設定 (`users/{uid}/settings/current`) を取得し、直近 N 日 (デフォルト 7 日) の現状評価を会話に返す。デフォルトは「トレーニング (HIIT + 筋トレ) / 睡眠 / 食事 / 体組成」の 4 軸で評価し、体組成の変化を他 3 軸と関連付けたうえで行動提案を出す。「今日のセッションを評価して」「直近の resting HR トレンド」「筋トレの重量推移を見せて」のような特定軸の依頼でも発火する。
argument-hint: <分析内容を自由記述。空ならデフォルトで「直近 7 日の 4 軸総合評価 (トレーニング[HIIT+筋トレ]/睡眠/食事/体組成) + 行動提案」を行う。例: "今日のセッションを評価して" / "筋トレの重量推移を見せて" / "直近 14 日">
allowed-tools: [Bash, Read, Write]
---

# analyze skill

## 0. 前提

- gcloud は project owner (個人 Gmail) で auth 済 (`adaptive-pulse-825a1`)。別環境では使えない。
- 個人健康データなので、抽出した中身を **repo にコミットしない / 第三者と共有しない** (`.claude/rules/secret.md`)。保存先の `tmp/` は `.gitignore` 済 + `.gitleaks.toml` allowlist 済。会話への出力も数字の要約・トレンド・所見までに留め、生 JSON dump を貼らない。

## 1. 実行 — `scripts/analyze.sh` を呼ぶ

データ取得 + 整形は全部 `scripts/analyze.sh` がやる。**毎回必ず実行する** (前回出力を再利用しない: dailyMetrics は `HealthSyncWorker` が定期的に上書きする live state なので、数分前のスナップショットでも陳腐化しうる)。

```bash
bash scripts/analyze.sh 7      # 直近 7 日
bash scripts/analyze.sh 14     # 直近 14 日
SLUG=hrv-trend bash scripts/analyze.sh 14   # summary md のファイル名 slug を変える
```

スクリプトが生成するもの (`tmp/analyze/$(TZ=Asia/Tokyo date +%F)/`):
- `sessions.json` / `workouts.json` / `metrics.json` / `settings.json` — Firestore raw response
- `summary__{HHMM}__{slug}.md` — stdout と同じ Markdown レポート
- 標準出力に `dailyMetrics` per-day 表 + `sessions` 表 + `workouts` (筋トレ) 表 + 派生集計 (体組成変化・食事収支と下限割れ・回復基準値・HIIT 集計・筋トレ種目別推移) + 現行 `settings` 表が出る

## 2. 会話への返し方 (デフォルト = 4 軸総合評価)

スクリプトの出力 (Markdown) を**そのまま貼らずに**、本 skill と同じディレクトリの `TEMPLATE.md` の構成で会話に返す。デフォルトは「トレーニング (HIIT + 筋トレ) / 睡眠 / 食事 / 体組成」の 4 軸で、**体組成の変化を起点**に他 3 軸で説明してから行動提案に進む。構成と各欄の書き方は TEMPLATE.md 側に集約してあり、ここでは繰り返さない。

- **数値の出所は script に一本化する**: 変化量・収支・下限割れ日・HIIT 集計・筋トレ種目別推移などの集計値は、summary md の「派生集計」セクションと各表から**転記のみ**する。会話側で jq や暗算による再集計をしない。テンプレートを埋めるのに必要な集計が script 出力に無ければ、その場しのぎの inline jq ではなく `scripts/analyze.sh` に集計を追加してから使う。
- AI が書くのは解釈・判定 (漸進的過負荷が効いているか、何が律速か、行動提案) のみ。
- 期間内に同一種目の比較対象が 1 回分しかなく漸進的過負荷を判定できなければ、日数を増やして (例: 14 日) 再実行してから判定する。

ユーザが軸を明示した依頼 (例: 「今日のセッションを評価して」) のときは 4 軸全部は無理に展開せず、その軸を中心にする (§3 を参照)。

### Markdown 表のフィールド名は日本語にしてよい (本文の表記揺れは避ける)

スクリプトの per-day 表はコード上のフィールド名 (`restingHeartRateBpm`, `hrvRmssdMs` 等) をそのまま列名にしている。会話で再掲する縮約表では、本文と用語を揃えるため日本語に直してよい (例: 安静時心拍 / 心拍揺らぎ / 睡眠 / 体重 / 体脂肪 / 摂取 / TDEE / 高強度滞在中央値)。

### 値の解釈

- `null` / `—`: その field をまだ Firestore に書き込んでいない、または当日まだ HC backfill 待ち (= 単に取れていない時点)。**「未連携バグ」「取得不全」と書かない**。
- `0`: 仕様上 0 を入れる経路がある (例: `fiberG` / `sugarG` / `sodiumMg` は今は 0 固定、`skinTemperatureDeltaC` も 0 が多い)。これも欠損として扱わない。
- `bodyFatPct = 0` だけは別: 過去日で値そのものを欠損として 0 を書く実装があった。直近 7 日では出ない。
- **カロリー収支のマイナスを「カロリー不足」とネガティブ表現しない**: ユーザは減量目的で意図的にマイナスを作っている。`intake - TDEE < 0` は中立に「マイナス収支」「意図的なマイナス」と書く。問題化するのは「PFC バランスが崩れている」「下限を割っている栄養素がある」など中身の話に限る (例: 脂質が体重 × 0.6 g/日 を下回る、タンパク質が体重 × 1.5 g/日 を下回る、等)。
- **session の brake = 疲労蓄積、と短絡しない**: brake 発動には 2 系統ある。(a) **探索の brake** = ユーザが `targetCycles` や `upperBpm/lowerBpm` を調整中で、設定がまだ合っていないだけ。期間内に `settings.updatedAtMs` が動いている / cycles 数が日々変わっている場合はこちら。(b) **疲労の brake** = 同一設定で複数日 brake が再現し、かつ 安静時心拍数 ↑ / 心拍の揺らぎ ↓ / 高強度滞在中央値の短縮が同時に出ているとき。デフォルト解釈は (a)。(b) と書くには 安静時心拍数 / 心拍の揺らぎ の同時悪化を根拠として添えること。

### 反証されたら自分の観測を最初に疑う

ユーザが「その所見は違う」「Firestore 直接見たら入っている」と言ったら、**仮説を盛らず**にまず単発取得して中身を会話に出す:

```bash
TOKEN=$(gcloud auth print-access-token); U=$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "https://firestore.googleapis.com/v1/projects/adaptive-pulse-825a1/databases/(default)/documents/users?pageSize=1" \
  | jq -r '.documents[0].name | split("/") | last')
curl -fsS -H "Authorization: Bearer $TOKEN" \
  "https://firestore.googleapis.com/v1/projects/adaptive-pulse-825a1/databases/(default)/documents/users/$U/dailyMetrics/<DATE>" \
  | jq '.updateTime, (.fields.json.stringValue | fromjson)'
```

## 3. 深掘り (ユーザが軸を指定したとき)

総合 7 日表で出ない/見にくい指標を聞かれたら、`scripts/analyze.sh` を希望日数で呼び直し、出力 raw JSON から該当軸だけ抜き出す。一度きりの深掘りなら inline jq を書いて構わないが、同種の深掘りを 2 回以上やったら (またはデフォルト評価で毎回使う集計になったら) `scripts/analyze.sh` の派生集計に昇格させる。

- **1 セッション評価**: per-cycle `highDurationsSec` / `recoveryDurationsSec`。high は長いほど良 (同負荷で上限到達が遅い = 向上)、recovery は短いほど良。**同一設定** (`upperBpm/lowerBpm` 不変) のときに後半で high 短縮 + recovery 延長 → 当日の疲労蓄積。期間内で `upperBpm/lowerBpm` を動かしているなら、まず設定差を理由として見る。
- **筋トレの種目別推移**: `workouts.json` の `entries[].trainingName` で種目を束ね、`sets[].weightKg` / `sets[].reps` の期間内推移を見る。同一種目で重量・回数が伸びていれば漸進的過負荷が効いている、横ばい・低下なら回復不足 (睡眠・食事軸を参照) か種目のマンネリを疑う。`skipped: true` の entry はその日「やらない」明示なので未実施として扱わず区別する。
- **安静時心拍数 (rHR) の基準値**: 過去 14 日中央値を基準値、当日 +5 bpm 超過なら疲労の蓄積を警告。
- **心拍の揺らぎ (HRV, RMSSD)**: 7 日移動平均を基準値、当日 -20% 以下なら過剰トレーニング徴候。
- **週次 in-zone time**: セッションごとに `sum(highDurationsSec)` を Z4-5 滞在時間として扱い、週で集計。週 30-60 分超過が続くなら過剰トレーニング risk。

## 4. 出力の作法

- 個人 uid を会話に出さない (スクリプトも出さない)。
- raw JSON / 生 stringValue を会話に貼らない。
- グラフはデフォルト生成しない (アプリ dashboard と重複)。要望時のみ matplotlib で `tmp/analyze/{today}/chart__<slug>.png` に書き出し、絶対 path を伝える。matplotlib が無い環境なら「未導入なのでスキップ」と 1 行返す。
- **送信前の構成チェック (必須)**: デフォルトの 4 軸評価では、送信直前に TEMPLATE.md の全セクションが埋まっているか — 特にトレーニング軸に HIIT と筋トレの**両方**の記述があり、筋トレ側に種目別推移 (漸進的過負荷の判定) が含まれるか — を確認する。欠けていたら補ってから送信する。
- **送信前セルフチェック (必須)**: 会話に返す直前に下記置換表を全件スキャンし、固有名詞 (`exerciseExtraKcal` 等のフィールド名、Firestore / Health Connect / Pixel Watch、HIIT / TDEE / BMR / BMI / bpm / kcal / g) 以外で英ジャーゴン・難語がそのまま残っていないか確認する。残っていたら平易語に置換、または括弧で日本語の言い換えを補ってから送信する。「ユーザが既に知っているはず」で省略しない (このチェックを省くと毎回ジャーゴンを温存して出す)。
- 説明文は日本語の平易な語で書く (英ジャーゴンを温存しない)。固有名詞 (`exerciseExtraKcal` 等のフィールド名、Firestore / Health Connect / Pixel Watch、HIIT / TDEE / BMR / BMI / bpm / kcal / g) はそのまま。それ以外の頻出置換:
  - deficit → マイナス収支 (「カロリー不足」と書かない — §2「値の解釈」参照)、surplus → プラス収支
  - intake → 摂取カロリー (フィールド名 `intakeKcal` を文中で使う場合はそのまま)
  - baseline → 基準値、snapshot → 取得時点のデータ、stale → 古い取得データ
  - delta → 差 / 変化量、median → 中央値
  - recovery debt → 疲労の蓄積、overtraining → 過剰トレーニング
  - (training) volume → トレーニング量 (重量 × 回数の総量)
  - progressive overload → 漸進的過負荷 (同じ種目で重量・回数を少しずつ増やしていく原則)
  - rHR (resting heart rate) → 安静時心拍数 (寝ている間の最低付近の心拍数。低いほど心臓に余裕がある)
  - HRV (heart rate variability / RMSSD) → 心拍の揺らぎ (心拍間隔のばらつき。高いほど自律神経が休めモードに入っている)
  - glycogen → グリコーゲン (筋肉に蓄えられた糖)
  - protein synthesis → タンパク質合成 (筋肉が作られる反応)
  - testosterone (T) → テストステロン (男性ホルモン、筋肉・性欲・気分を司る)
  - T3 → 甲状腺ホルモン (代謝の速さを決める)
  - cortisol → コルチゾール (ストレスホルモン、筋肉を分解する方向に働く)
  - leptin → レプチン (脂肪細胞が出す満腹ホルモン、低いと空腹増・代謝低下)
  - catabolic → 分解方向 (筋肉を壊す側)、anabolic → 合成方向 (筋肉を作る側)
  - 徐脂肪 / lean body mass (LBM) → 筋肉など脂肪以外の重さ (筋肉・骨・水分・内臓)。「徐脂肪のロス」は「筋肉が落ちる」と書く
  - DOMS → 遅発性筋肉痛 (運動翌日〜2 日後に出る筋肉痛)
  - omega-3 / omega-6 → オメガ 3 / オメガ 6 (必須脂肪酸の種類)
  - prostaglandin → プロスタグランジン (炎症を調整する局所ホルモン)
