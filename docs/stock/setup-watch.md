# Pixel Watch サイドロード手順 (人間の作業)

Wi-Fi ADB で別環境 (WSL) から `scripts/sideload.sh watch` を叩く前の、ウォッチ側
ワンタイム設定。Pixel Watch は USB が無いので Wi-Fi ADB が唯一の sideload 経路。

## 1. 初回ペアリング (Pixel Watch アプリ)

1. Android phone に Google Play の「Pixel Watch」アプリを入れて起動
2. アプリの指示に従いウォッチと Bluetooth ペアリングし、Google アカウントを同期
3. ジムで使う Wi-Fi に**ウォッチを接続**しておく (ADB は同じ Wi-Fi LAN で繋ぐ)

## 2. 開発者モード + Wireless debugging を有効化 (一度だけ)

ウォッチ本体で操作する。

1. 設定 → System → About → **Build number を 7 回タップ** → developer モード on
2. 設定 → Developer options → **Wireless debugging を ON**
3. Wireless debugging 画面で **「Pair new device」** をタップ
   - **IP アドレス & ポート** (例: `192.168.1.42:43251`) と
   - **6 桁のペアリング code** が表示される (この画面を開いたままにする)

ペアリング code 画面を閉じると code が無効化されるので、次の `adb pair` まで
画面を開いたままにする。

## 3. WSL 側で adb pair → connect (一度だけ)

別環境 (sideload を回す WSL) で実行する。同じ Wi-Fi LAN にいること。

```bash
# 1. ペアリング (1 回だけ。code 入力プロンプトが出る)
adb pair <Pair-IP>:<Pair-Port>
# Enter pairing code: <ウォッチ画面の 6 桁>

# 2. 接続 (Wireless debugging トップに表示されている IP:Port の方を使う。
#    Pair-Port とは別ポート)
adb connect <Connect-IP>:<Connect-Port>

# 3. 確認
adb devices    # <Connect-IP>:<Port>  device  と出れば OK
```

ウォッチを再起動したり Wi-Fi が切れると `adb connect` だけやり直しになる。
ペアリング自体は永続するので `adb pair` は基本一度だけ。

## 4. APK を install

```bash
bash scripts/sideload.sh watch
```

phone も同時に adb 接続されているときは Pixel Watch の serial を指定する:

```bash
adb devices                                    # 両方の serial を確認
ANDROID_SERIAL=<watch-serial> bash scripts/sideload.sh watch
```

## トラブルシューティング

- `adb devices` に出てこない: ウォッチと PC が同じ LAN にいるか / ウォッチ側
  Wireless debugging が ON か / `adb connect` を打ち直す。
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: 既に別署名の APK が入っている。`sideload.sh`
  が自動で `adb uninstall` → 再 install まで面倒を見る。
- Wi-Fi が切れて `offline` 表示になる: `adb disconnect` → `adb connect` し直す。
