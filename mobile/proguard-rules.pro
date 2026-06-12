# R8 追加ルール (mobile)。

# health-services と同様、protobuf javalite 系のリフレクションを保護する
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
