---
name: verify
description: watch アプリの変更を Wear OS エミュレータで実際に駆動して確認する手順 (ビルド・起動・画面操作・スクリーンショット)
---

# verify (adaptive-pulse)

watch (`:app`) の変更はエミュレータで実駆動して確認する。ドメインロジックは JVM テストで担保されるので、ここで見るのは画面・遷移・振動語彙などの結線。

## 手順

```bash
# 1. エミュレータ起動 (AVD は scripts/setup_android.sh が作る adaptivepulse_wear / adaptivepulse_phone)
nohup emulator -avd adaptivepulse_wear -no-snapshot-save -no-audio -no-boot-anim > /tmp/emulator.log 2>&1 &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done; echo BOOTED'

# 2. ビルド & インストール & 起動
./gradlew :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.wakuwaku3.adaptivepulse/.MainActivity

# 3. 操作と観察 (画面は 384x384)
adb shell input tap <x> <y>
adb shell input swipe 192 300 192 80 200   # リストのスクロール
adb exec-out screencap -p > shot.png       # スクリーンショット (Read で目視)
adb logcat -d -s AdaptivePulse | tail      # アプリログ (セッション開始 plan 等)
```

## 知見

- 権限ダイアログはエミュレータでは出ないことが多い (拒否されても合成心拍で動く設計)。
- 心拍は Health Services の合成データ (`ExerciseClient (ELLIPTICAL) で計測` とログに出る)。値はゆっくり上下し、閾値到達を待つ検証は分単位かかる。時間制メニューの終了確認は実時間で待つ (バックグラウンド sleep + screencap の時間差取得が楽)。
- Idle 画面の主要座標 (目安): 選択ラベル (192,211) / ▶ (147,290) / ⚙ (237,290)。Running の Stop は (192,328)。
- phone (`:mobile`) のライブ画面は watch とのペアリングが必要でエミュレータ単体では届かない。phone UI は `adaptivepulse_phone` AVD + デバッグビルドの「Show demo session」「Show demo dashboard」で見る。
- Firestore Rules の検証は `cd rules-test && npm test` (Firebase Emulator、数秒で終わる)。
