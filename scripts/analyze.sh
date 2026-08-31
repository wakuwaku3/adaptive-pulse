#!/usr/bin/env bash
# Firestore から HIIT セッション/筋トレ記録/日次健康指標/現行設定を取得し、直近 N 日の現状評価
# (per-day 表 + sessions 表 + workouts 表 + 派生集計 + 現行 settings) を Markdown で stdout に出す。
# 会話に返す数値はすべて本スクリプトが成型する (会話側での再集計を不要にするのが目的)。
#
# 出力先 (raw JSON + summary md) は tmp/analyze/{YYYY-MM-DD}/。
# 個人健康データなので tmp/ は .gitignore + .gitleaks.toml allowlist 済の前提。
#
# 前提: gcloud は project owner で auth 済 (adaptive-pulse-825a1)。jq 1.7+。
set -euo pipefail

DAYS=${1:-7}
SLUG=${SLUG:-current-state}
PROJECT_ID=adaptive-pulse-825a1
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
TODAY=$(TZ=Asia/Tokyo date +%F)
NOW_HM=$(TZ=Asia/Tokyo date +%H%M)
OUT_DIR=${OUT_DIR:-$REPO_ROOT/tmp/analyze/$TODAY}
mkdir -p "$OUT_DIR"

err() { echo "ERROR: $*" >&2; exit 1; }

TOKEN=$(gcloud auth print-access-token 2>/dev/null || true)
[ -n "$TOKEN" ] || err "gcloud auth 未設定。'gcloud auth login' を実行してください。"

BASE="https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"

UID_DOC=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/users?pageSize=1") \
  || err "users collection の list に失敗 (token 失効?)"
U=$(echo "$UID_DOC" | jq -r '.documents[0].name | split("/") | last')
[ -n "$U" ] && [ "$U" != "null" ] || err "uid を取り出せませんでした"

FROM_DATE=$(TZ=Asia/Tokyo date -d "$((DAYS-1)) days ago" +%F)
START_MS=$(TZ=Asia/Tokyo date -d "$FROM_DATE 00:00:00" +%s%3N)
END_MS=$(TZ=Asia/Tokyo date -d "$TODAY 23:59:59.999" +%s%3N)

# sessions
SESS_QUERY=$(jq -nc --arg s "$START_MS" --arg e "$END_MS" '{
  structuredQuery: {
    from: [{collectionId: "sessions"}],
    where: { compositeFilter: { op: "AND", filters: [
      { fieldFilter: { field: {fieldPath: "startedAtMs"}, op: "GREATER_THAN_OR_EQUAL", value: {integerValue: $s}}},
      { fieldFilter: { field: {fieldPath: "startedAtMs"}, op: "LESS_THAN_OR_EQUAL",    value: {integerValue: $e}}}
    ]}},
    orderBy: [{field: {fieldPath: "startedAtMs"}, direction: "ASCENDING"}]
  }
}')
curl -fsS -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X POST "$BASE/users/$U:runQuery" -d "$SESS_QUERY" > "$OUT_DIR/sessions.json"

# workouts (筋トレ記録)
WORKOUT_QUERY=$(jq -nc --arg s "$START_MS" --arg e "$END_MS" '{
  structuredQuery: {
    from: [{collectionId: "workouts"}],
    where: { compositeFilter: { op: "AND", filters: [
      { fieldFilter: { field: {fieldPath: "startedAtMs"}, op: "GREATER_THAN_OR_EQUAL", value: {integerValue: $s}}},
      { fieldFilter: { field: {fieldPath: "startedAtMs"}, op: "LESS_THAN_OR_EQUAL",    value: {integerValue: $e}}}
    ]}},
    orderBy: [{field: {fieldPath: "startedAtMs"}, direction: "ASCENDING"}]
  }
}')
curl -fsS -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X POST "$BASE/users/$U:runQuery" -d "$WORKOUT_QUERY" > "$OUT_DIR/workouts.json"

# dailyMetrics
METR_QUERY=$(jq -nc --arg from "$FROM_DATE" --arg to "$TODAY" '{
  structuredQuery: {
    from: [{collectionId: "dailyMetrics"}],
    where: { compositeFilter: { op: "AND", filters: [
      { fieldFilter: { field: {fieldPath: "date"}, op: "GREATER_THAN_OR_EQUAL", value: {stringValue: $from}}},
      { fieldFilter: { field: {fieldPath: "date"}, op: "LESS_THAN_OR_EQUAL",    value: {stringValue: $to}}}
    ]}},
    orderBy: [{field: {fieldPath: "date"}, direction: "ASCENDING"}]
  }
}')
curl -fsS -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X POST "$BASE/users/$U:runQuery" -d "$METR_QUERY" > "$OUT_DIR/metrics.json"

# settings/current
curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$BASE/users/$U/settings/current" > "$OUT_DIR/settings.json"

SUMMARY_PATH="$OUT_DIR/summary__${NOW_HM}__${SLUG}.md"

# タンパク質下限のアンカー体重: 設定の目標体重、未設定なら身長から BMI 22 標準体重。
# 根拠と閾値の出典は docs/stock/nutrition-guidelines.md (ダッシュボードの帯と同一基準)。
TARGET_W=$(jq -r '
  .fields.json.stringValue | fromjson
  | (.targetWeightKg
     // (if .heightCm != null then ((.heightCm / 100) as $m | $m * $m * 22) else null end))
' "$OUT_DIR/settings.json")

# ---- レポート生成 (markdown) ----
{
  echo "# adaptive-pulse 現状評価 ($TODAY $(TZ=Asia/Tokyo date +%H:%M) JST)"
  echo
  echo "期間: $FROM_DATE .. $TODAY ($DAYS 日)"
  echo

  echo "## dailyMetrics"
  echo
  jq -r '
    def f1: if type == "number" then (. * 10 | round / 10 | tostring) else "—" end;
    def f0: if type == "number" then (. | round | tostring) else "—" end;
    def cell(v): if v == null then "—" else (v|tostring) end;

    [.[] | select(.document) | .document.fields.json.stringValue | fromjson]
    | sort_by(.date)
    | ("| field | " + ([.[] | .date] | join(" | ")) + " |"),
      ("|---|" + ([.[] | "---"] | join("|")) + "|"),
      (
        [
          ["rHR",                  "restingHeartRateBpm",   "f0"],
          ["HRV (ms)",             "hrvRmssdMs",            "f1"],
          ["avgHR",                "avgHeartRateBpm",       "f0"],
          ["minHR",                "minHeartRateBpm",       "f0"],
          ["maxHR",                "maxHeartRateBpm",       "f0"],
          ["sleep (min)",          "sleepDurationMin",      "f0"],
          ["sleep deep",           "sleepDeepMin",          "f0"],
          ["sleep REM",            "sleepRemMin",           "f0"],
          ["sleep light",          "sleepLightMin",         "f0"],
          ["sleep awake",          "sleepAwakeMin",         "f0"],
          ["steps",                "steps",                 "f0"],
          ["distance (m)",         "distanceMeters",        "f0"],
          ["floors",               "floorsClimbed",         "f0"],
          ["elevation (m)",        "elevationGainedMeters", "f1"],
          ["activeKcal",           "activeCaloriesKcal",    "f0"],
          ["totalKcal (HC)",       "totalCaloriesKcal",     "f0"],
          ["basalKcal",            "basalCaloriesKcal",     "f0"],
          ["TDEE",                 "tdeeKcal",              "f0"],
          ["exerciseExtraKcal",    "exerciseExtraKcal",     "f0"],
          ["weight (kg)",          "weightKg",              "f1"],
          ["bodyFat %",            "bodyFatPct",            "f1"],
          ["intakeKcal",           "intakeKcal",            "f0"],
          ["protein (g)",          "proteinG",              "f1"],
          ["fat (g)",              "fatG",                  "f1"],
          ["carbs (g)",            "carbsG",                "f1"],
          ["fiber (g)",            "fiberG",                "f1"],
          ["sugar (g)",            "sugarG",                "f1"],
          ["sodium (mg)",          "sodiumMg",              "f0"],
          ["resp rate",            "respiratoryRateAvg",    "f1"],
          ["skinTempΔ (°C)",       "skinTemperatureDeltaC", "f1"],
          ["SpO2 avg %",           "spo2AvgPct",            "f1"]
        ] as $rows
        | . as $docs
        | $rows[] as $r
        | "| " + $r[0] + " | "
          + (
              [$docs[] | (.[$r[1]]) |
                if $r[2] == "f1" then (if type=="number" then (.*10|round/10|tostring) else "—" end)
                else (if type=="number" then (.|round|tostring) else "—" end)
                end
              ] | join(" | ")
            )
          + " |"
      )
  ' "$OUT_DIR/metrics.json"

  echo
  echo "## sessions"
  echo
  SESS_COUNT=$(jq '[.[] | select(.document)] | length' "$OUT_DIR/sessions.json")
  if [ "$SESS_COUNT" = "0" ]; then
    echo "(該当期間にセッションなし)"
  else
    echo "| 日時 (JST) | cyc | brake | avg/max bpm | high_med | rec_med | upper/lower | tgtC |"
    echo "|---|---|---|---|---|---|---|---|"
    jq -r '
      def median: if length == 0 then null else (sort | .[length/2|floor]) end;
      def fmt_secs: if . == null then "—" else "\(. | round)s" end;
      [.[] | select(.document) | .document.fields.json.stringValue | fromjson]
      | sort_by(.startedAtMs)
      | .[]
      | (((.startedAtMs/1000) + 9*3600) | strftime("%Y-%m-%d %H:%M")) as $when
      | ((.highDurationsSec // []) | median) as $hmed
      | ((.recoveryDurationsSec // []) | median) as $rmed
      | "| " + $when
        + " | " + (.cycles|tostring) + "/" + (.plannedCycles|tostring)
        + " | " + (.fatigueBrake|tostring)
        + " | " + ((.avgBpm // "—")|tostring) + "/" + ((.maxBpm // "—")|tostring)
        + " | " + ($hmed | fmt_secs)
        + " | " + ($rmed | fmt_secs)
        + " | " + (.config.upperBpm|tostring) + "/" + (.config.lowerBpm|tostring)
        + " | " + (.config.targetCycles|tostring)
        + " |"
    ' "$OUT_DIR/sessions.json"
  fi

  echo
  echo "## workouts (筋トレ記録)"
  echo
  WORKOUT_COUNT=$(jq '[.[] | select(.document)] | length' "$OUT_DIR/workouts.json")
  if [ "$WORKOUT_COUNT" = "0" ]; then
    echo "(該当期間に筋トレ記録なし)"
  else
    echo "| 日時 (JST) | gym | 種目 (セット数) | 総トレーニング量 (kg×回) | 状態 |"
    echo "|---|---|---|---|---|"
    jq -r '
      [.[] | select(.document) | .document.fields.json.stringValue | fromjson]
      | sort_by(.startedAtMs)
      | .[]
      | (((.startedAtMs/1000) + 9*3600) | strftime("%Y-%m-%d %H:%M")) as $when
      | ((.entries // []) | map(select(.skipped != true))) as $active
      | ($active | map(
          (.sets // []) as $s
          | (.trainingName) + " (" + ($s|length|tostring) + "set)"
        ) | join(", ")) as $exSummary
      | ($active | map((.sets // []) | map((.weightKg // 0) * .reps)) | flatten | (add // 0)) as $volume
      | "| " + $when
        + " | " + .gymName
        + " | " + (if $exSummary == "" then "—" else $exSummary end)
        + " | " + ($volume | round | tostring)
        + " | " + (if .endedAtMs == null then "進行中" else (.endReason // "—") end)
        + " |"
    ' "$OUT_DIR/workouts.json"
  fi

  echo
  echo "## 派生集計 (会話にはここの数値を転記し、会話側で再集計しない)"
  echo
  jq -r --argjson tw "$TARGET_W" '
    def f1: if . == null then "—" else (. * 10 | round / 10 | tostring) end;
    def f0: if . == null then "—" else (. | round | tostring) end;
    def sgn1: (. * 10 | round / 10) | if . > 0 then "+\(.)" else tostring end;
    def sgn0: round | if . > 0 then "+\(.)" else tostring end;
    def median: if length == 0 then null else (sort | .[length/2|floor]) end;
    def avg: if length == 0 then null else (add / length) end;
    ([.[] | select(.document) | .document.fields.json.stringValue | fromjson] | sort_by(.date)) as $m
    | ([$m[].weightKg | select(. != null)]) as $w
    | ([$m[].bodyFatPct | select(. != null and . != 0)]) as $bf
    | ([$m[].restingHeartRateBpm | select(. != null)]) as $rhr
    | ([$m[].hrvRmssdMs | select(. != null)]) as $hrv
    | ($w | last) as $wLast
    | (if $tw != null then ($tw * 1.6 * 10 | round / 10) else null end) as $pFloor
    | (if $wLast != null then ($wLast * 0.5 * 10 | round / 10) else null end) as $fFloor
    | (if $pFloor != null then [$m[] | select(.proteinG != null and .proteinG < $pFloor) | .date[5:]] else [] end) as $pLow
    | (if $fFloor != null then [$m[] | select(.fatG != null and .fatG < $fFloor) | .date[5:]] else [] end) as $fLow
    | (if $wLast != null then ($wLast * 2 | round) else null end) as $cBandLo
    | (if $cBandLo != null then [$m[] | select(.carbsG != null and .carbsG < $cBandLo) | .date[5:]] else [] end) as $cLow
    | ([$m[].carbsG | select(. != null)]) as $carbs
    | ([$m[] | select(.weightKg != null and .bodyFatPct != null and .bodyFatPct != 0)]) as $comp
    | ([$m[].sleepDurationMin | select(. != null)]) as $sleep
    | ([$m[] | select(.sleepDurationMin != null and .sleepDurationMin < 420) | .date[5:]]) as $sleepShort
    | ([$m[] | select(.intakeKcal != null and .tdeeKcal != null)]) as $days
    | "### 体組成 (期間の最初の実測 → 最後の実測)",
      (if ($w | length) > 0 then "- 体重: \($w | first | f1) → \($w | last | f1) kg (\(($w | last) - ($w | first) | sgn1))" else "- 体重: データなし" end),
      (if ($bf | length) > 0 then "- 体脂肪率: \($bf | first | f1) → \($bf | last | f1) % (\(($bf | last) - ($bf | first) | sgn1))" else "- 体脂肪率: データなし" end),
      (if ($comp | length) > 1 then
        ($comp | first) as $c0 | ($comp | last) as $c1
        | ($c0.weightKg * $c0.bodyFatPct / 100) as $f0 | ($c1.weightKg * $c1.bodyFatPct / 100) as $f1x
        | ($c0.weightKg - $f0) as $l0 | ($c1.weightKg - $f1x) as $l1
        | "- 内訳 (\($c0.date[5:]) → \($c1.date[5:])): 脂肪量 \($f0 | f1) → \($f1x | f1) kg (\($f1x - $f0 | sgn1)) / 脂肪以外の重さ \($l0 | f1) → \($l1 | f1) kg (\($l1 - $l0 | sgn1))"
       else empty end),
      "",
      "### 食事 (摂取と TDEE が両方ある \($days | length) 日分。当日は記録途中でありうる)",
      (if ($days | length) > 0 then
        ([$days[].intakeKcal] | add) as $in | ([$days[].tdeeKcal] | add) as $out
        | "- 摂取合計 \($in | f0) kcal / TDEE 合計 \($out | f0) kcal / 収支 \($in - $out | sgn0) kcal (日平均 \(($in - $out) / ($days | length) | sgn0) kcal)"
       else "- 摂取/TDEE のそろった日なし" end),
      (if $pFloor != null then "- タンパク質が下限 (目標体重 \($tw | f1) kg × 1.6 = \($pFloor) g/日) を下回った日: \(if ($pLow | length) > 0 then ($pLow | join(", ")) else "なし" end)" else empty end),
      (if $fFloor != null then "- 脂質が下限 (体重 \($wLast | f1) kg × 0.5 = \($fFloor) g/日) を下回った日: \(if ($fLow | length) > 0 then ($fLow | join(", ")) else "なし" end)" else empty end),
      (if $cBandLo != null then "- 炭水化物が「ふつう」帯の下端 (体重 \($wLast | f1) kg × 2 = \($cBandLo) g/日) を下回った日: \(if ($cLow | length) > 0 then "\($cLow | length) 日 (\($cLow | join(", ")))" else "なし" end)" else empty end),
      (if ($carbs | length) > 0 then "- 炭水化物 中央値 \($carbs | median | f0) g/日 (最小 \($carbs | min | f0) / 最大 \($carbs | max | f0))" else empty end),
      (if ($days | length) > 0 then
        "- 日別収支: " + ([$days[] | "\(.date[5:]) \(.intakeKcal - .tdeeKcal | sgn0)"] | join(" / "))
       else empty end),
      (if ($days | length) > 0 then
        ([$days[] | select((.intakeKcal - .tdeeKcal) < -500) | .date[5:]]) as $bigDef
        | "- 収支が -500 kcal を下回った日: \(if ($bigDef | length) > 0 then "\($bigDef | length) 日 (\($bigDef | join(", ")))" else "なし" end)"
       else empty end),
      "",
      "### 回復 (期間内)",
      "- 安静時心拍数 中央値: \($rhr | median | f0) bpm / 心拍の揺らぎ 平均: \($hrv | avg | f1) ms",
      (if ($sleep | length) > 0 then "- 睡眠時間 中央値: \($sleep | median | f0) 分 (最小 \($sleep | min | f0) / 最大 \($sleep | max | f0)) / 7 時間未満の日: \(if ($sleepShort | length) > 0 then "\($sleepShort | length) 日 (\($sleepShort | join(", ")))" else "なし" end)" else empty end)
  ' "$OUT_DIR/metrics.json"

  echo
  jq -r '
    ([.[] | select(.document) | .document.fields.json.stringValue | fromjson]) as $s
    | ([$s[] | select(.cycles == .plannedCycles and .cycles > 0)] | length) as $done
    | ([$s[] | select(.fatigueBrake == true)] | length) as $brake
    | ([$s[] | ((.highDurationsSec // []) | add // 0)] | add // 0) as $zoneSec
    | "### HIIT (期間内)",
      "- セッション \($s | length) 件 (完走 \($done) / brake 発動 \($brake))",
      "- 高強度ゾーン滞在の合計: \($zoneSec / 60 | round) 分 (目安: 週 30-60 分)"
  ' "$OUT_DIR/sessions.json"

  echo
  echo "### 筋トレ種目別推移 (セル = その日のトップセット重量×回数。量 = 全セットの重量×回数の合計)"
  echo
  if [ "$WORKOUT_COUNT" = "0" ]; then
    echo "(該当期間に筋トレ記録なし)"
  else
    jq -r '
      [.[] | select(.document) | .document.fields.json.stringValue | fromjson
        | (((.startedAtMs / 1000) + 9*3600) | strftime("%Y-%m-%d")) as $d
        | (.entries // [])[] | select(.skipped != true)
        | {d: $d, n: .trainingName, sets: [(.sets // [])[] | {w: (.weightKg // 0), r: (.reps // 0)}]}
        | select((.sets | length) > 0)
      ]
      | if length == 0 then "(セット記録のある種目なし)" else
          ([.[].d] | unique | sort) as $dates
          | group_by(.n)
          | map({
              n: .[0].n,
              cells: (group_by(.d) | map({ (.[0].d): {
                top: ([.[].sets[]] | max_by([.w, .r]) | "\(.w * 10 | round / 10)kg×\(.r)"),
                vol: ([.[].sets[] | .w * .r] | add | round)
              }}) | add)
            })
          | ("| 種目 | " + ($dates | map(.[5:] | sub("-"; "/")) | join(" | ")) + " |"),
            ("|---|" + ($dates | map("---") | join("|")) + "|"),
            (.[] | . as $row | "| \(.n) | " + ([$dates[] | $row.cells[.] | if . == null then "—" else "\(.top) (量 \(.vol))" end] | join(" | ")) + " |")
        end
    ' "$OUT_DIR/workouts.json"
  fi

  echo
  echo "## 現行 settings"
  echo
  jq -r '
    .fields.json.stringValue | fromjson
    | to_entries
    | map(select(.key != "updatedAtMs"))
    | "| key | value |",
      "|---|---|",
      (.[] | "| " + .key + " | " + (.value|tostring) + " |")
  ' "$OUT_DIR/settings.json"
  echo
  echo "(updatedAtMs: $(jq -r '.fields.json.stringValue | fromjson | .updatedAtMs' "$OUT_DIR/settings.json") = $(TZ=Asia/Tokyo date -d @$(($(jq -r '.fields.json.stringValue | fromjson | .updatedAtMs' "$OUT_DIR/settings.json")/1000)) '+%Y-%m-%d %H:%M JST'))"

  echo
  echo "---"
  echo "raw: $OUT_DIR/{sessions,workouts,metrics,settings}.json"
  echo "this summary: $SUMMARY_PATH"
} | tee "$SUMMARY_PATH"
