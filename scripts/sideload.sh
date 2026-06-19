#!/usr/bin/env bash
# 最新 release (or 引数で指定した tag) の指定 form factor の APK を、
# adb devices に出ている対象端末全てに sideload する。
#
# 使い方:
#   bash scripts/sideload.sh                 # watch と phone を両方 (デフォルト)
#   bash scripts/sideload.sh all             # 同上
#   bash scripts/sideload.sh phone           # 最新 release の phone APK だけ
#   bash scripts/sideload.sh watch           # 最新 release の watch APK だけ
#   bash scripts/sideload.sh watch v1.2.4    # watch を指定 tag で
#   bash scripts/sideload.sh all v1.2.4      # 両方とも指定 tag で (form 共通 tag は無いので普通は省略)
#
# 前提: gh / adb が PATH にあり、対象端末が adb devices に "device" として
# 見えていること (Wi-Fi ADB or USB)。Pixel Watch は初回のみ developer mode
# + Wireless debugging を有効化し、adb pair / connect が必要 (docs/stock/setup-watch.md)。
# 端末の選択 (form ごとに独立に判定):
#   - ANDROID_SERIAL が export されていればその 1 台のみ対象 (form 関わらず)。
#   - そうでなければ ro.build.characteristics で FORM (phone|watch) を判定し、
#     一致する全端末に install する (watch が 2 台繋がっていれば 2 台とも入る)。
#     実機とエミュレータが両方一致した場合は実機のみを対象にする。
# 署名違い (debug ↔ release 切替) で update install が失敗したら一度
# uninstall して再 install する (ローカル pending データは消えるが、Firestore
# に正本があるので問題なし)。
set -euo pipefail

cd "$(dirname "$0")/.."

ARG="${1:-all}"
TAG="${2:-}"
PKG="io.github.wakuwaku3.adaptivepulse"

case "$ARG" in
  phone) FORMS=(phone) ;;
  watch) FORMS=(watch) ;;
  all)   FORMS=(watch phone) ;;
  *)
    echo "usage: $0 [phone|watch|all] [TAG]" >&2
    exit 2
    ;;
esac

# 接続端末の一覧は form ループ間で共通。1 回だけ取って使い回す
echo "==> 接続端末"
adb devices
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
  echo "adb devices に 'device' 状態の端末がいません。Wi-Fi ADB の接続を確認してください" >&2
  exit 1
fi

# 各端末の form factor を 1 回だけ判定してキャッシュする (form ループで再 adb shell しない)
declare -A DEVICE_KIND
for d in "${DEVICES[@]}"; do
  chars="$(adb -s "$d" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
  case "$chars" in *watch*) DEVICE_KIND[$d]="watch" ;; *) DEVICE_KIND[$d]="phone" ;; esac
done

install_form() {
  local form="$1"
  local pattern="adaptive-pulse-${form}-*.apk"

  local TARGETS=()
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    TARGETS=("$ANDROID_SERIAL")
  else
    local CAND=()
    local CAND_REAL=()
    for d in "${DEVICES[@]}"; do
      if [ "${DEVICE_KIND[$d]}" = "$form" ]; then
        CAND+=("$d")
        case "$d" in emulator-*) ;; *) CAND_REAL+=("$d") ;; esac
      fi
    done

    if [ "${#CAND[@]}" -eq 0 ]; then
      echo "[$form] 対象端末が adb 接続にいません (接続: ${DEVICES[*]})。skip" >&2
      return 0
    elif [ "${#CAND_REAL[@]}" -gt 0 ]; then
      TARGETS=("${CAND_REAL[@]}")
    else
      TARGETS=("${CAND[@]}")
    fi
  fi
  echo "==> [$form] install 対象 (${#TARGETS[@]} 台): ${TARGETS[*]}"

  echo "==> [$form] 古い APK を片付け"
  rm -f adaptive-pulse-${form}-*.apk

  local tag="$TAG"
  if [ -z "$tag" ]; then
    # form 別 tag 列の latest を取る (watch-v* / phone-v*)。
    # 単に `gh release download --pattern` だと他 form の最新 release がヒットして
    # APK が無く失敗するため、tag を明示する。
    tag="$(gh release list --limit 30 --json tagName \
      --jq "[.[].tagName | select(startswith(\"${form}-v\"))][0]")"
    [ -n "$tag" ] || { echo "[$form] ${form}-v* な release が見つかりません" >&2; return 1; }
  fi
  echo "==> [$form] release から APK を DL ($tag)"
  gh release download "$tag" --pattern "$pattern"

  local apk
  apk="$(ls -t adaptive-pulse-${form}-*.apk 2>/dev/null | head -1)"
  [ -n "$apk" ] || { echo "[$form] APK が見つかりません" >&2; return 1; }

  local rc=0
  for serial in "${TARGETS[@]}"; do
    echo "==> [$form] install: $apk → $serial"
    local log
    log="$(mktemp)"
    if adb -s "$serial" install -r "$apk" 2>&1 | tee "$log" | grep -q "Success"; then
      echo "==> [$form] 完了 (上書き install): $serial"
      rm -f "$log"
      continue
    fi
    if grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match" "$log"; then
      echo "==> [$form] 署名が違うため uninstall → 再 install (ローカル pending データは消える): $serial"
      adb -s "$serial" uninstall "$PKG" || true
      if adb -s "$serial" install "$apk"; then
        echo "==> [$form] 完了 (uninstall → install): $serial"
        rm -f "$log"
        continue
      fi
    fi
    echo "==> [$form] install 失敗: $serial" >&2
    cat "$log" >&2
    rm -f "$log"
    rc=1
  done
  return $rc
}

overall=0
for f in "${FORMS[@]}"; do
  install_form "$f" || overall=1
done
exit $overall
