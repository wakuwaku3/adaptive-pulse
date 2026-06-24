---
description: UI の言語・デザイントーン
paths:
  - "app/src/main/**"
  - "mobile/src/main/**"
---

# UI

## 共通 (:app watch + :mobile phone)

- **UI 文言は英語にする** (日本語よりスタイリッシュ、というユーザの強い嗜好。2026-06-11)。コード内コメントやドキュメントは日本語のまま。
- 例外: ライブセッション中の engine 行動提案 (ペース緩める / 中断) は日本語固定 (2026-06-24)。短い英語より日本語の方が「理由を込めた説明文」の読み下しが速く、誤読しないため。提案文は `core.SessionSuggestion` の `title` / `reason` に閉じ込め、他の UI には波及させない。
- フォント・色はその場しのぎで選ばず、`ui/theme/` (AdaptivePulseTheme / MobileColors) に集約して一貫させる。新しい色・スタイルが必要になったらまず theme に追加する。
- デザイントーン: 黒背景 (OLED) + フェーズを直感色で塗り分け (高強度=コーラル / 回復=ミント / 完了=ゴールド)。
- アクションボタンはテキストラベルのチップではなく、記号グリフ 1 文字 (`⋮` `‹` `+` `−` 等) のボタンにする。複数並べるときは横並び (大きいボタン・縦積みを嫌うユーザ嗜好。2026-06-11)。
- 例外: アクションの意味がグリフ 1 文字では伝わらないとユーザが判断したケース (例: Settings の `Resync` ボタン、2026-06-23) はテキストラベル可。グリフ優先は OS chrome 由来の操作 (戻る・メニュー・増減) に効くが、ドメイン語彙が要る操作はテキストにする。
- バンドルフォントのライセンスは `licenses/` に置く。
- **全画面は `Scaffold` (or `Surface`) で包む**。Material3 の `Text` は `color` 未指定時に `LocalContentColor` を見るが、Surface 配下じゃないと default が `Color.Black` になり、dark theme でも本文が黒のまま不可視になる。SignIn 画面のような単発の `Column` も Scaffold で包むこと。
- Theme は `onBackground` / `onSurface` 等の `onXxx` 色を明示的に定義する。
- **派生値 (移動平均・前日比・MA-Nd など) を併記するときは、raw 値と同じ band 判定で着色する** (2026-06-24)。主値だけ band 色で派生値が `TextDim` 固定のような不揃いは禁止。Today カードの `sub`、MiniChart 凡例の dot/value、Settings の補足値など、テキスト位置に関わらず適用する。raw の単位と派生値の単位が違う (例: Weight kg → BMI band) 場合は派生値を band 軸 (BMI) に換算してから判定する。
- **値表記の "内数"**: 主値の隣に半角スペース + 括弧で派生値を内包する 1 行表記を指す (例: ラベル `Weight (MA d3)` / 値 `91.4 (91.5) kg`)。`(` の前には必ず半角スペースを入れる。2 行に分けたり別行 sub にしたりしない。Compose では `AnnotatedString` + `SpanStyle` で raw / MA をそれぞれ band 色に、括弧・単位は通常色に塗り分ける。

## :app (Wear OS) 特有

- 運動中の一瞥で読めること (大きい数字・高コントラスト) を優先する。
- アクションボタンは `IconActionButton` (視覚 32dp の小円) を使う。

## :mobile (Android phone) 特有

- **`TopAppBar` の `actions` は「画面の primary action だけ icon で直置き、それ以外は overflow menu (`⋮ IconButton + DropdownMenu`) に畳む」** (Android 標準の慣習)。e.g., HISTORY 画面の `Refresh` (↻) は visible icon、`Settings` / `Sign out` などは overflow へ。`TextButton` を並べる UI は使わない。
- **タブで主画面を切り替えない**。主画面 1 枚 (`HISTORY`) + overflow から `Settings` などサブ画面へ遷移、戻りは `BackHandler` でシステム back に乗せる。タブは「同等な並列ビュー」のときだけ使う (本アプリは該当しない)。
- 副画面の `TopAppBar` には `navigationIcon` (`‹`) で back を明示。
