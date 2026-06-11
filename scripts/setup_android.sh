#!/usr/bin/env bash
# Android SDK を WSL 内 (~/Android/Sdk) にセットアップする (idempotent)。
# devbox は CLI ツール (jdk 等) 用で、Android SDK はこのスクリプトで管理する
# (nixpkgs の androidsdk は unfree かつ構成が特殊なため採らない)。
# エミュレータは /dev/kvm + WSLg で WSL 内実行する前提 (docs/stock/tech.md)。
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
# cmdline-tools の "latest" zip はビルド番号入り URL で配布される
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if ! command -v java >/dev/null 2>&1; then
  echo "java が PATH にありません。repo 直下で direnv (devbox) を有効にしてから実行してください" >&2
  exit 1
fi

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> cmdline-tools を取得: $SDK"
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -sSfL "$CLT_URL" -o "$tmp/clt.zip"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

sdkmanager() { "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "$@"; }

echo "==> ライセンス同意"
# sdkmanager が先に exit すると yes が SIGPIPE で死に pipefail に引っかかるため、
# yes 側の終了コードだけ無視する
(yes || true) | sdkmanager --licenses >/dev/null

echo "==> SDK コンポーネントを導入"
sdkmanager --install \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "emulator" >/dev/null

# Wear OS の x86_64 system image は API レベルごとに提供が異なるため、
# 利用可能な最新を動的に選ぶ
echo "==> Wear OS system image を探索"
wear_image="$(sdkmanager --list 2>/dev/null \
  | grep -oE 'system-images;android-[0-9]+;android-wear;x86_64' \
  | sort -t- -k2 -V | tail -1)"
if [ -n "$wear_image" ]; then
  echo "==> $wear_image を導入"
  sdkmanager --install "$wear_image" >/dev/null

  avd_home="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
  if [ ! -d "$avd_home/adaptivepulse_wear.avd" ] && ! "$SDK/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q adaptivepulse_wear; then
    echo "==> AVD adaptivepulse_wear を作成"
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
      --name adaptivepulse_wear \
      --package "$wear_image" \
      --device wearos_small_round >/dev/null || \
      echo "WARN: AVD 作成に失敗 (--device 名が変わった可能性)。avdmanager list device で確認して手動作成してください" >&2
  fi
else
  echo "WARN: Wear OS system image が見つからず。sdkmanager --list で確認してください" >&2
fi

# エミュレータ (qemu/Qt) が要求するシステムライブラリ。WSL の素の Ubuntu には無い
missing_pkgs=""
for lib_pkg in "libpulse.so.0:libpulse0" "libnss3.so:libnss3" "libnspr4.so:libnspr4" \
  "libSM.so.6:libsm6" "libICE.so.6:libice6" "libxkbfile.so.1:libxkbfile1"; do
  lib="${lib_pkg%%:*}"
  pkg="${lib_pkg##*:}"
  ldconfig -p 2>/dev/null | grep -q "$lib" || missing_pkgs="$missing_pkgs $pkg"
done
if [ -n "$missing_pkgs" ]; then
  echo "WARN: エミュレータ実行に必要なライブラリが不足しています:" >&2
  echo "  sudo apt-get install -y$missing_pkgs" >&2
fi

if [ ! -e /dev/kvm ]; then
  echo "WARN: /dev/kvm が無いためエミュレータは動きません (Windows 側で nested virtualization を確認)" >&2
elif [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
  echo "WARN: /dev/kvm に権限がありません: sudo usermod -aG kvm $USER して WSL を再起動してください" >&2
fi

echo "==> 完了。ANDROID_HOME=$SDK (.envrc が PATH を通します)"
