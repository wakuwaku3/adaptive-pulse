#!/usr/bin/env bash
# Firestore から HIIT セッション/日次健康指標/現行設定を取得し、直近 N 日の現状評価
# (per-day 表 + sessions 表 + 現行 settings) を Markdown で stdout に出す。
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
  echo "raw: $OUT_DIR/{sessions,metrics,settings}.json"
  echo "this summary: $SUMMARY_PATH"
} | tee "$SUMMARY_PATH"
