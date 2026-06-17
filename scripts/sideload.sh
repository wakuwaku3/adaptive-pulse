#!/usr/bin/env bash
# 最新 release (or 引数で指定した tag) の指定 form factor の APK を、
# adb devices に出ている対象端末全てに sideload する。
#
# 使い方:
#   bash scripts/sideload.sh phone           # 最新 release の phone APK
#   bash scripts/sideload.sh watch           # 最新 release の watch APK
#   bash scripts/sideload.sh watch v1.2.4    # 指定 tag
#
# 前提: gh / adb が PATH にあり、対象端末が adb devices に "device" として
# 見えていること (Wi-Fi ADB or USB)。Pixel Watch は初回のみ developer mode
# + Wireless debugging を有効化し、adb pair / connect が必要 (docs/stock/setup-watch.md)。
# 端末の選択:
#   - ANDROID_SERIAL が export されていればその 1 台のみ対象。
#   - そうでなければ ro.build.characteristics で FORM (phone|watch) を判定し、
#     一致する全端末に install する (watch が 2 台繋がっていれば 2 台とも入る)。
#     実機とエミュレータが両方一致した場合は実機のみを対象にする。
# 署名違い (debug ↔ release 切替) で update install が失敗したら一度
# uninstall して再 install する (ローカル pending データは消えるが、Firestore
# に正本があるので問題なし)。
set -euo pipefail

cd "$(dirname "$0")/.."

FORM="${1:-}"
TAG="${2:-}"
PKG="io.github.wakuwaku3.adaptivepulse"

case "$FORM" in
  phone|watch) ;;
  *)
    echo "usage: $0 <phone|watch> [TAG]" >&2
    exit 2
    ;;
esac
PATTERN="adaptive-pulse-${FORM}-*.apk"

echo "==> 接続端末"
adb devices
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
  echo "adb devices に 'device' 状態の端末がいません。Wi-Fi ADB の接続を確認してください" >&2
  exit 1
fi

TARGETS=()
if [ -n "${ANDROID_SERIAL:-}" ]; then
  TARGETS=("$ANDROID_SERIAL")
else
  # ro.build.characteristics に "watch" を含むかで Wear OS / phone を判定し、
  # 要求された FORM に一致する端末を全て対象にする
  CAND=()
  CAND_REAL=()
  for d in "${DEVICES[@]}"; do
    chars="$(adb -s "$d" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
    kind="phone"
    case "$chars" in *watch*) kind="watch" ;; esac
    if [ "$kind" = "$FORM" ]; then
      CAND+=("$d")
      case "$d" in emulator-*) ;; *) CAND_REAL+=("$d") ;; esac
    fi
  done

  if [ "${#CAND[@]}" -eq 0 ]; then
    echo "$FORM の端末が adb 接続にいません (接続: ${DEVICES[*]})" >&2
    exit 1
  elif [ "${#CAND_REAL[@]}" -gt 0 ]; then
    # 実機が 1 台でも一致するなら実機のみ対象 (エミュレータと混ぜない)
    TARGETS=("${CAND_REAL[@]}")
  else
    # エミュレータしか一致しなかった
    TARGETS=("${CAND[@]}")
  fi
fi
echo "==> install 対象 ($FORM, ${#TARGETS[@]} 台): ${TARGETS[*]}"

echo "==> 古い $FORM APK を片付け"
rm -f adaptive-pulse-${FORM}-*.apk

if [ -z "$TAG" ]; then
  # form 別 tag 列の latest を取る (watch-v* / phone-v*)。
  # 単に `gh release download --pattern` だと他 form の最新 release がヒットして
  # APK が無く失敗するため、tag を明示する。
  TAG="$(gh release list --limit 30 --json tagName \
    --jq "[.[].tagName | select(startswith(\"${FORM}-v\"))][0]")"
  [ -n "$TAG" ] || { echo "${FORM}-v* な release が見つかりません" >&2; exit 1; }
fi
echo "==> release から APK を DL ($TAG)"
gh release download "$TAG" --pattern "$PATTERN"

apk="$(ls -t adaptive-pulse-${FORM}-*.apk 2>/dev/null | head -1)"
[ -n "$apk" ] || { echo "APK が見つかりません" >&2; exit 1; }

# 1 台でも失敗したら exit 1 で抜けるが、ループの途中でも残り端末は試行する
fail=0
for serial in "${TARGETS[@]}"; do
  echo "==> install: $apk → $serial"
  log="$(mktemp)"
  if adb -s "$serial" install -r "$apk" 2>&1 | tee "$log" | grep -q "Success"; then
    echo "==> 完了 (上書き install): $serial"
    rm -f "$log"
    continue
  fi
  if grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match" "$log"; then
    echo "==> 署名が違うため uninstall → 再 install (ローカル pending データは消える): $serial"
    adb -s "$serial" uninstall "$PKG" || true
    if adb -s "$serial" install "$apk"; then
      echo "==> 完了 (uninstall → install): $serial"
      rm -f "$log"
      continue
    fi
  fi
  echo "==> install 失敗: $serial" >&2
  cat "$log" >&2
  rm -f "$log"
  fail=1
done

exit $fail
