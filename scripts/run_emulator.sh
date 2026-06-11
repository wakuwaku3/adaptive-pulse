#!/usr/bin/env bash
# WSL 内で Wear OS エミュレータを起動する (画面は WSLg)。
# 前提: scripts/setup_android.sh 済み + ユーザが kvm グループに所属していること
# (groups で確認。無ければ sudo usermod -aG kvm $USER して WSL 再起動)。
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"

if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
  echo "/dev/kvm に権限がありません。kvm グループ所属と WSL 再起動を確認してください" >&2
  exit 1
fi

# ホスト GPU でレンダリングできない場合は -gpu swiftshader_indirect を付けて再実行する
exec "$SDK/emulator/emulator" -avd adaptivepulse_wear -no-audio -no-boot-anim "$@"
