---
name: analyze
description: adaptive-pulse の Firestore に貯まった HIIT セッション履歴 (`users/{uid}/sessions/*`)、日次健康指標 (`users/{uid}/dailyMetrics/*`)、現行設定 (`users/{uid}/settings/current`) を取得し、直近 N 日 (デフォルト 7 日) の現状評価を会話に返す。デフォルトは「セッション / 睡眠 / 食事 / 体組成」の 4 軸で評価し、体組成の変化を他 3 軸と関連付けたうえで行動提案を出す。「今日のセッションを評価して」「直近の resting HR トレンド」のような特定軸の依頼でも発火する。
argument-hint: <分析内容を自由記述。空ならデフォルトで「直近 7 日の 4 軸総合評価 (セッション/睡眠/食事/体組成) + 行動提案」を行う。例: "今日のセッションを評価して" / "直近 14 日">
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
- `sessions.json` / `metrics.json` / `settings.json` — Firestore raw response
- `summary__{HHMM}__{slug}.md` — stdout と同じ Markdown レポート
- 標準出力に `dailyMetrics` per-day 表 + `sessions` 表 + 現行 `settings` 表が出る

## 2. 会話への返し方 (デフォルト = 4 軸総合評価)

スクリプトの出力 (Markdown) を**そのまま貼らずに**、以下の構造で会話に返す。デフォルトは「セッション / 睡眠 / 食事 / 体組成」の 4 軸。**体組成の変化を起点**に、それを他 3 軸 (セッション・睡眠・食事) で説明する形で現状評価を組み立ててから、行動提案に進む。

1. **体組成の変化 (起点)**: 期間内の体重 / 体脂肪率 / BMI の変化方向と大きさを 1-2 行で。横ばいなら横ばいと書く。
2. **体組成変化の説明 — 他 3 軸との関連付け**:
   - **セッション**: 高強度滞在中央値・recovery 中央値・cycle 達成率・brake 発動・週次 in-zone 時間 → トレーニング負荷と消費。
   - **睡眠**: 睡眠時間・deep/REM・心拍の揺らぎ (HRV)・安静時心拍数 (rHR) → 回復品質。
   - **食事**: intake / TDEE / 収支のマイナス・プラス (中立表現で、「不足」とネガティブに書かない) / PFC バランス (タンパク質充足、脂質下限 ~体重 × 0.6 g/日 を割っていないか) → エネルギー収支と合成基質。
   各軸 1-3 行で、体組成が「なぜそう動いたか」を説明する根拠として書く。
3. **必要な表だけ抜粋**: `dailyMetrics` 全体は長いので、上記 3 軸の根拠になる行だけを 1 表に圧縮して貼る (列名は日本語可)。`sessions` 表は短いのでそのまま貼ってよい。
4. **行動提案 (1-2 個)**: 2 で見えた律速 (例: 睡眠不足が回復を律速、protein 不足が筋合成を律速、カロリー超過が体脂肪を律速) を解消する具体策。設定変更を提案する場合は現行 `settings` の値と並べて差分で示す。
5. **末尾**: スクリプトが保存した summary md の絶対パスを 1 行で添える。

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

総合 7 日表で出ない/見にくい指標を聞かれたら、`scripts/analyze.sh` を希望日数で呼び直し、出力 raw JSON から該当軸だけ抜き出す。Inline で jq を書いて構わない (この場合は会話完結で問題ない深さなので)。

- **1 セッション評価**: per-cycle `highDurationsSec` / `recoveryDurationsSec`。high は長いほど良 (同負荷で上限到達が遅い = 向上)、recovery は短いほど良。**同一設定** (`upperBpm/lowerBpm` 不変) のときに後半で high 短縮 + recovery 延長 → 当日の疲労蓄積。期間内で `upperBpm/lowerBpm` を動かしているなら、まず設定差を理由として見る。
- **安静時心拍数 (rHR) の基準値**: 過去 14 日中央値を基準値、当日 +5 bpm 超過なら疲労の蓄積を警告。
- **心拍の揺らぎ (HRV, RMSSD)**: 7 日移動平均を基準値、当日 -20% 以下なら過剰トレーニング徴候。
- **週次 in-zone time**: セッションごとに `sum(highDurationsSec)` を Z4-5 滞在時間として扱い、週で集計。週 30-60 分超過が続くなら過剰トレーニング risk。

## 4. 出力の作法

- 個人 uid を会話に出さない (スクリプトも出さない)。
- raw JSON / 生 stringValue を会話に貼らない。
- グラフはデフォルト生成しない (アプリ dashboard と重複)。要望時のみ matplotlib で `tmp/analyze/{today}/chart__<slug>.png` に書き出し、絶対 path を伝える。matplotlib が無い環境なら「未導入なのでスキップ」と 1 行返す。
- **送信前セルフチェック (必須)**: 会話に返す直前に下記置換表を全件スキャンし、固有名詞 (`exerciseExtraKcal` 等のフィールド名、Firestore / Health Connect / Pixel Watch、HIIT / TDEE / BMR / BMI / bpm / kcal / g) 以外で英ジャーゴン・難語がそのまま残っていないか確認する。残っていたら平易語に置換、または括弧で日本語の言い換えを補ってから送信する。「ユーザが既に知っているはず」で省略しない (このチェックを省くと毎回ジャーゴンを温存して出す)。
- 説明文は日本語の平易な語で書く (英ジャーゴンを温存しない)。固有名詞 (`exerciseExtraKcal` 等のフィールド名、Firestore / Health Connect / Pixel Watch、HIIT / TDEE / BMR / BMI / bpm / kcal / g) はそのまま。それ以外の頻出置換:
  - deficit → マイナス収支 (「カロリー不足」と書かない — §2「値の解釈」参照)、surplus → プラス収支
  - intake → 摂取カロリー (フィールド名 `intakeKcal` を文中で使う場合はそのまま)
  - baseline → 基準値、snapshot → 取得時点のデータ、stale → 古い取得データ
  - delta → 差 / 変化量、median → 中央値
  - recovery debt → 疲労の蓄積、overtraining → 過剰トレーニング
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
