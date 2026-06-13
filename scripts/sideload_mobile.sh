#!/usr/bin/env bash
# 最新 release (or 引数で指定した tag) の :mobile APK を、adb devices に
# 出ている端末に sideload する。
#
# 使い方:
#   bash scripts/sideload_mobile.sh           # latest release
#   bash scripts/sideload_mobile.sh v1.2.4    # 指定 tag
#
# 前提: gh / adb が PATH にあり、対象端末が adb devices に "device" として
# 見えていること (Wi-Fi ADB or USB)。複数端末あれば ANDROID_SERIAL=... を export。
# 署名違い (debug ↔ release 切替) で update install が失敗したら一度
# uninstall して再 install する (ローカル pending データは消えるが、Firestore
# に正本があるので問題なし)。
set -euo pipefail

cd "$(dirname "$0")/.."

TAG="${1:-}"
PKG="io.github.wakuwaku3.adaptivepulse"

echo "==> 接続端末"
adb devices
if ! adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  echo "adb devices に 'device' 状態の端末がいません。Wi-Fi ADB の接続を確認してください" >&2
  exit 1
fi

echo "==> 古い phone APK を片付け"
rm -f adaptive-pulse-phone-*.apk

echo "==> release から APK を DL${TAG:+ ($TAG)}"
if [ -n "$TAG" ]; then
  gh release download "$TAG" --pattern 'adaptive-pulse-phone-*.apk'
else
  gh release download --pattern 'adaptive-pulse-phone-*.apk'
fi

apk="$(ls -t adaptive-pulse-phone-*.apk 2>/dev/null | head -1)"
[ -n "$apk" ] || { echo "APK が見つかりません" >&2; exit 1; }

echo "==> install: $apk"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
if adb install -r "$apk" 2>&1 | tee "$log" | grep -q "Success"; then
  echo "==> 完了 (上書き install)"
  exit 0
fi

if grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match" "$log"; then
  echo "==> 署名が違うため uninstall → 再 install (ローカル pending データは消える)"
  adb uninstall "$PKG"
  adb install "$apk"
  echo "==> 完了 (uninstall → install)"
  exit 0
fi

echo "==> install 失敗:" >&2
cat "$log" >&2
exit 1
