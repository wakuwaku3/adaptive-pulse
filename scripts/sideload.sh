#!/usr/bin/env bash
# 最新 release (or 引数で指定した tag) の指定 form factor の APK を、
# adb devices に出ている端末に sideload する。
#
# 使い方:
#   bash scripts/sideload.sh phone           # 最新 release の phone APK
#   bash scripts/sideload.sh watch           # 最新 release の watch APK
#   bash scripts/sideload.sh watch v1.2.4    # 指定 tag
#
# 前提: gh / adb が PATH にあり、対象端末が adb devices に "device" として
# 見えていること (Wi-Fi ADB or USB)。Pixel Watch は初回のみ developer mode
# + Wireless debugging を有効化し、adb pair / connect が必要 (docs/stock/setup-watch.md)。
# 端末の選択は次の順:
#   1. ANDROID_SERIAL が export されていればそれ
#   2. 非 emulator (実機) が 1 台だけならそれ
#   3. それ以外は曖昧として ANDROID_SERIAL を要求してエラー
# 同じ applicationId で phone / watch を扱うため、複数の実機 (phone + watch) が
# adb 上に同時にいる場合は ANDROID_SERIAL の指定が必須になる。
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

if [ -n "${ANDROID_SERIAL:-}" ]; then
  SERIAL="$ANDROID_SERIAL"
else
  REAL=()
  for d in "${DEVICES[@]}"; do
    case "$d" in emulator-*) ;; *) REAL+=("$d") ;; esac
  done
  if [ "${#REAL[@]}" -eq 1 ]; then
    SERIAL="${REAL[0]}"
  elif [ "${#REAL[@]}" -eq 0 ]; then
    SERIAL="${DEVICES[0]}"
  else
    # phone と watch が同時接続のときは ro.build.characteristics で form factor を判別する
    # (Wear OS は "watch" を含む。それ以外は phone とみなす)
    MATCH=()
    for d in "${REAL[@]}"; do
      chars="$(adb -s "$d" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
      kind="phone"
      case "$chars" in *watch*) kind="watch" ;; esac
      if [ "$kind" = "$FORM" ]; then MATCH+=("$d"); fi
    done
    if [ "${#MATCH[@]}" -eq 1 ]; then
      SERIAL="${MATCH[0]}"
      echo "==> $FORM と判定: $SERIAL (他: ${REAL[*]/$SERIAL})"
    elif [ "${#MATCH[@]}" -eq 0 ]; then
      echo "実機の中に $FORM が見つかりません (接続: ${REAL[*]})" >&2
      exit 1
    else
      echo "$FORM が複数あり選べません: ${MATCH[*]}。ANDROID_SERIAL=<serial> を export して再実行してください" >&2
      exit 1
    fi
  fi
fi
echo "==> install 先: $SERIAL ($FORM)"

echo "==> 古い $FORM APK を片付け"
rm -f adaptive-pulse-${FORM}-*.apk

echo "==> release から APK を DL${TAG:+ ($TAG)}"
if [ -n "$TAG" ]; then
  gh release download "$TAG" --pattern "$PATTERN"
else
  gh release download --pattern "$PATTERN"
fi

apk="$(ls -t adaptive-pulse-${FORM}-*.apk 2>/dev/null | head -1)"
[ -n "$apk" ] || { echo "APK が見つかりません" >&2; exit 1; }

echo "==> install: $apk"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
if adb -s "$SERIAL" install -r "$apk" 2>&1 | tee "$log" | grep -q "Success"; then
  echo "==> 完了 (上書き install)"
  exit 0
fi

if grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match" "$log"; then
  echo "==> 署名が違うため uninstall → 再 install (ローカル pending データは消える)"
  adb -s "$SERIAL" uninstall "$PKG"
  adb -s "$SERIAL" install "$apk"
  echo "==> 完了 (uninstall → install)"
  exit 0
fi

echo "==> install 失敗:" >&2
cat "$log" >&2
exit 1
