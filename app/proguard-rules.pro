# R8 追加ルール。release ビルドで実行時クラッシュが出たらここに追加する。

# health-services-client は protobuf javalite のフィールドをリフレクションで読む。
# R8 がフィールドを削除/リネームすると実行時に
# 「Field packageName_ ... not found」で能力照会が落ち、実センサー経路が
# 使えなくなる (2026-06-12 にエミュレータの release ビルドで実際に発生)。
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
