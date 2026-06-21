# ペース (cadence) メトリクス追加の検討

## 動機

ユーザの実運用上の課題:

> 運動中の適切なペース配分がわからない。メトロノーム的にリズムを刻めばペース配分しやすいのではないか。

現状のメトリクスは HR + カロリーのみで、ペース (クロストレーナーの cadence) は計測も提示もしていない。これを追加すべきか・どう設計するかを検討する。

別セッションで進行中の「watch → phone アプリへの状態同期」と統合する想定で、表現先は **phone アプリ画面** を主に置く (音・光は周囲に迷惑になるため使えない / 運動中の腕は振っていて watch 画面は見られない)。

## そもそもペースを気にする理由

HR トリガー設計の補助として有効。ペース管理そのものが目的ではない。

- **HR の遅延を補う**: 光学式 HR は 10〜30 秒遅延する (要件 §非機能要件)。スタート時に踏み込み過ぎると、HR には反映されない間に過負荷状態に入り、気付いた時には上限に張り付いて回復に時間がかかる。ペースは即時に制御できるので、HR の遅延を先回りで補える。
- **疲労ブレーキの比較を意味あるものにする**: 既存疲労ブレーキは「高強度所要時間」(下限から上限到達までの時間) で判定する (要件 §疲労ブレーキ)。この所要時間は踏み込みの強さに敏感で、今日強く踏めば早く上限到達=疲労扱い、緩く踏めば遅く到達=元気扱い、と誤判定する。ペースを揃えれば負荷が固定され、所要時間差が真に心肺フィットネス・疲労を反映する。
- **副次効果**: 同一ペース下での HR 応答は HR 到達より早く出るため、疲労の早期検知シグナルになり得る (フェーズ B 以降の発展余地)。

逆に、ペース管理を**フェーズ切替のリアルタイムトリガー**には使わない (HR トリガー設計の単純さを壊す)。

## RPS の単位 convention

クロストレーナーで観測される RPS には 2 つの流派がある。

- **「左右 1 サイクル = 1 stride」流派** (機械系の一部)
- **「片足の踏み込み 1 回 = 1 step」流派** (マシン表示・ランニング業界・Health Services `STEPS_PER_MINUTE` もこちら)

→ 本プロジェクトでは **step convention に揃える** (マシン表示および Health Services と一致させる)。以降の SPM / RPS はすべて step convention。

ユーザの実機マシン表示の参考実測 (制御目標ではなく分布把握用):

| フェーズ | SPM | 備考 |
|---|---|---|
| WARM-UP | ~80 | 軽くアップ |
| 高強度 | 140〜150 | 自然に出てくる値 |
| 回復 | ~70 | 自然に出てくる値 |

これは「自然に選ばれる cadence」(Marsh & Martin 1997) の参考分布であり、目標値ではない。目標は §ペースをどう決めるか の制御ループで決まる。

## ペースをどう決めるか

ペースは独立に設定する目標値ではなく、**目標 phase duration を達成するための制御変数**として、cycle 毎・セッション毎に動的に決まる。

### Anchor: target phase duration (生理学的に定まる窓)

| フェーズ | 推奨 duration | 根拠 |
|---|---|---|
| 高強度 | 45〜90 秒 | Buchheit & Laursen 2013, 心肺ストレス十分 × 中枢疲労未到達 |
| 回復 | 30〜75 秒 | HRR の最も急峻な区間 (Cole et al. 1999), 完全冷却前 |

この duration 窓は個人差・fitness 変動・抵抗設定に依存せず**不変**。これが system の真の anchor になる。

### 制御ループ (cycle 毎更新 + セッション間持ち越し)

```
セッション開始:
  if 前回セッションあり: target_cadence ← 前回最終の target_cadence
  else:                  target_cadence ← seed

サイクル完了毎:
  observed_duration = (フェーズ完了時刻 − フェーズ開始時刻)
  if observed_duration < d_min:
    target_cadence -= k × (d_min - observed_duration)   # 速すぎ → target を下げる
  elif observed_duration > d_max:
    target_cadence += k × (observed_duration - d_max)   # 遅すぎ → target を上げる
  else:
    no change                                            # sweet spot

セッション終了:
  最終 target_cadence を保存 → 次セッションの初期値に
```

- パラメタ感: `k ≈ 0.2 SPM/秒` (1 サイクルで最大 ±10 SPM 程度の遷移)。チューニングは実測で。
- 高強度・回復それぞれに独立した target と制御ループ。

### Day-1 seed (超保守的にしない・少し下に置く)

実測 (140/70) よりやや低めに置き、制御ループに上向き探索させる:

- **高強度 seed: 130 SPM**
- **回復 seed: 65 SPM**

「上から探索」より「下から探索」を選ぶ理由: 過剰負荷リスクを避ける + 上向きは安全。設定画面で編集可能にする (要件 §設定値 と整合)。

### Day-1 anchor を捨てた理由

初期 baseline 観察値をその後の target に固定する案は採らない:

1. **初日の信頼性が低い**: フォーム未確立 / 抵抗設定が探索的 / 緊張・気合過剰。
2. **初期適応が急峻**: 未訓練→訓練適応で VO2max は 4〜6 週で 10〜15% 改善 (Hickson 1981, Holloszy 1973)。Day-1 baseline は 2〜4 週で systematically ぬるくなる。
3. **rolling median でも追従が遅れる**: 直近 N セッションの中央値は 2〜3 週遅れで追従、結局ラグる。

baseline を**観察した過去**に置く限り fitness 改善を追えない。anchor を**達成したい experience (target duration)** に置けば、過去に依存せず常に「今日の体」へ適応する。

### 既存疲労ブレーキとの役割分担

| 機構 | 時間スケール | anchor | 役割 |
|---|---|---|---|
| **疲労ブレーキ** | 同セッション内 (cycle 単位) | **同セッションの baseline cycle** | 急性疲労検出 → セッション終了 |
| **cadence 適応制御** | クロスセッション (× 同セッション補助) | **絶対 target duration (45-90s)** | fitness / 抵抗 / 慢性変動への追従 |

両者は独立。疲労ブレーキは「今日の体内」の異常検出、cadence 制御は「個体の能力」のトラッキング。

### ガードレール (急激な負荷の防止)

target cadence を機械的に追うだけだと「急激な負荷」問題が残る。phone 画面側で:

- **立ち上がり ramp**: 高強度フェーズ突入時、表示する current target を recovery → high まで 10〜15 秒で線形遷移。いきなり target 値に到達させない。
- **オーバーシュート色**: current が target を超え、かつ HR が上限まで残り 5〜10bpm のとき警告色。
- **回復のクリープ防止**: recovery target は HR が下限に近付くにつれ漸減せず一定。降りすぎを防ぐ。

## どう測るか

`STEPS_PER_MINUTE` 優先、ダメなら加速度生信号からの自前検出。

- Pixel Watch の Health Services には `DataType.STEPS_PER_MINUTE` がある。徒歩・ランの「片足着地 = 1 step」と同じ convention のはずで、機材表示とほぼ同じ単位で取れる**可能性が高い**。
- ただしクロストレーナーは足が接地しない動きなので、step として認識されないリスクがある。実機で要検証。
- 自前検出が必要なら生加速度を取り、3〜5 秒窓で短時間 FFT または peak detection。枯れた処理で、JVM 単体テストで合成波形を流して検証できる (ドメインロジックを Android フレームワークから分離する本 repo の方針と合う)。
- **精度より安定性**: 1 秒ごとの瞬時値はノイジーなので、3〜5 秒窓の median をフィルタとして掛ける。

## どう体験として伝えるか (phone 画面)

phone を機材のコンソール上に置いて、視野の端で同期する想定。音・光は使わない。

候補:

- **拍動円 (推奨)**: ターゲット RPS で円が pulse。色で差分を表現 (within=ニュートラル / 速すぎ=ホット / 遅すぎ=クール)。周辺視で読める・実装が軽い。
- **流れるバー**: 左→右にマーカーが等速移動し脚の動きと位相同期させる。位相検出が要るため重い。最初は不要。
- **波形ストリーム**: 過去 10〜30 秒の cadence ライン。終了画面・振り返り向け。運動中は不要。

運動中は **拍動円** を中心にして、ターゲット数字 + 現値 + 簡単なズレ色で十分。位相同期は overkill。

watch 側 UI は変えない (引き続き心拍数字最優先)。

## watch → phone 同期スキーマへの追加フィールド

別セッションで組む watch → phone 状態同期に、ペースのために足すフィールド:

| フィールド | 型 | 更新頻度 | 用途 |
|---|---|---|---|
| `currentCadenceSpm` | Double | sample 毎 (3〜5 秒窓 median 後) | 拍動円の current 表示 |
| `targetCadenceSpm` | Double | cycle 毎更新 | 拍動円の target tempo |
| `phase` | enum | 遷移時 | フェーズ色・ramp 制御 |
| `cycleIndex` | Int | サイクル完了時 | 制御ループのトリガー |

phone 側は `targetCadenceSpm` から拍動円の tempo を計算、`currentCadenceSpm - targetCadenceSpm` から色判定。

### 永続化が必要なもの (watch 側)

- **`lastSessionTargetCadenceHigh: Double`** / **`lastSessionTargetCadenceRecovery: Double`**: 前回セッション最終 target を DataStore に保存。次セッション開始時の初期値として使う。
- 初回 (= 値なし) は seed (130 / 65) を使う。

## 段階的ロードマップ

要件の MVP (HR トリガー + 疲労ブレーキ) はそのまま。拡張フェーズで段階的に。

| フェーズ | 内容 | 完了基準 |
|---|---|---|
| **A: 計測 + 配信** | Health Services の `STEPS_PER_MINUTE` で cadence 取得、`SessionLiveSnapshot` に `currentCadenceSpm` を載せて phone へ配信。履歴にも保存 | クロストレーナーで意味のある値が返ることを実機で確認、phone で current 表示できる |
| **B: 適応 target + UI** | 制御ループ (target_duration 駆動 + 前回持ち越し) を実装、`targetCadenceSpm` を配信、phone に拍動円 UI、立ち上がり ramp | target を追って duration が安定する体感が出る |
| **C: ガードレール** | オーバーシュート色 / 回復クリープ防止 / 設定画面で seed 編集可 | 過剰負荷ケースが UI で防止される |
| **D: 疲労シグナル化** | 同一 cadence での HR 応答変化から疲労を早期検知。既存疲労ブレーキの第 3 シグナル | 既存疲労ブレーキより早く・偽陽性少なく検知できる |

Phase A は計測と配信のみ。Phase B で初めて制御ループが入る (Day-1 にも target が出る)。Phase D は B の運用データが溜まってから判断。

## 2026-06-21 追記: 3 段フォールバック + tier lock 実装

実機 (ジム) で `STEPS_PER_MINUTE` が走らず、SPM が全く計測できなかった。これを受けて
精度の高い順に取れた値を使い、取れなかったら次のロジックに落ちる構成にした。

精度順位 (高 → 低):

| tier | ソース | 鮮度判定 | 実装 |
|---|---|---|---|
| 1 | `DataType.STEPS_PER_MINUTE` (watch の歩行検出が出す瞬時 rate) | 5 秒 | `HealthServicesExerciseSource` |
| 2 | `DataType.STEPS_TOTAL` 差分 (10 秒窓で SPM を再構成) | 10 秒 | `StepsDeltaCadenceEstimator` |
| 3 | 加速度 magnitude の peak detection (5 秒窓・最小 peak 間隔 200ms) | 3 秒 | `AccelerometerCadenceEstimator` + `AccelerometerCadenceSource` |

### tier lock (セッション内では同じロジックで計測)

セッション中に tier がフラフラ切り替わると SPM が階段状に飛んで使いものにならない。
セッション開始から **discovery 窓 (15 秒)** 内に最良 tier を決め、以降は固定する。

- discovery 中: 鮮度内で最良 priority の tier を返す (下がらず上がる方向のみ移行)。
- 窓経過: その瞬間に fresh な最良 tier を確定。以降はセッション中ずっとその tier だけを使う。
- 確定 tier が一時的に stale になったら null を流す (移行はしない)。
- どの tier も fresh でないまま窓が経過したら、最初に fresh になった tier で即時 lock。

状態マシンは `:core` の `TieredCadenceLock` に分離し JVM 単体テストで網羅。
`HealthServicesExerciseSource` は 1 つの ExerciseClient + 注入された加速度 Flow から
全 3 tier を観測し、lock の現在 tier に従って SPM を `ExerciseSample` に詰める。

## 残課題 / 次セッションへの引き継ぎ

- 実機で何 tier が確定するかをジムで観察する。lock 後の SPM が rolling median (5 秒) でも揺れすぎる場合、tier 別に窓サイズ調整。
- tier 3 (加速度) は常時 50Hz サンプリング。電池影響は要観察。lock 確定後の不要な listener 停止 (e.g., T1 lock 後の加速度停止) は様子見。
- どの tier で測ったかを `SessionRecord` に残す案 (後追い集計のため)。要件への反映と合わせて検討。
- 制御ループのパラメタ (`k`, `d_min`, `d_max`) は実測でチューニング。初期値: `k = 0.2 SPM/秒`、`d_min/d_max` は §ペースをどう決めるか の表通り。
- seed (130/65) は要件 §設定値 に編集可能項目として追加する想定。要件本体への反映は Phase C のタイミング。
- phone 画面の拍動円デザイン (色・サイズ・配置) は別セッションで具体化。
- 要件 §拡張 にペース管理項目を追記するかは **Phase B の運用が安定してから** (今は flow に留め、stock 昇格は早い)。

## 関連

- 要件: `docs/stock/requirements.md` §非機能要件 (HR 遅延), §疲労ブレーキ, §拡張
- 既存疲労ブレーキ設計の経緯: `docs/notes/20260620__recovery-and-karvonen/index.md`
- phone 同期: 別セッションで進行中 (本ノート時点では未着手)
- **2026-06-20 追記**: phone live session 統合セッション (`docs/notes/20260620__phone-live-session/index.md`) と合流。`/session/live` の DTO (`SessionLiveSnapshot`) に Phase A の `currentRps` を相乗りさせ、Health Services の `STEPS_PER_MINUTE` 経路を実装した。`targetRps` と拍動円 UI は Phase A の実測結果次第で別セッション。
