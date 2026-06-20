package io.github.wakuwaku3.adaptivepulse.mobile.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.wakuwaku3.adaptivepulse.mobile.MainActivity
import io.github.wakuwaku3.adaptivepulse.mobile.R

/**
 * watch がセッションを開始したとき、phone を起こしてライブ画面を前面化する。
 *
 * Android 10+ はバックグラウンドから直接 Activity を起動できないため、
 * full-screen intent 付き高優先度通知を経由する (アラーム・着信アプリ等と同じ方式)。
 * `USE_FULL_SCREEN_INTENT` permission が未許可 (Android 14+) でも通知は出るので、
 * 利用者が通知をタップすればライブ画面に飛べる fallback になっている。
 */
object LiveSessionLauncher {

    private const val CHANNEL_ID = "live_session"
    private const val NOTIFICATION_ID = 2001

    fun notifyAndLaunch(context: Context) {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            // 既に起動済みなら同じインスタンスを前面化 (Activity の onNewIntent で拾える)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Session running")
            .setContentText("Open AdaptivePulse to follow live")
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pending)
            // full-screen intent: アプリが背景にあるとき OS にライブ画面を割り込み起動させる
            .setFullScreenIntent(pending, true)
            .build()

        // Android 13+ は POST_NOTIFICATIONS が要る。未許可なら通知は出さない (アプリは壊さない)。
        // ユーザが MainActivity に到達した時点で許可ダイアログを 1 度出すので、次セッションから出る
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    /** ライブ画面を閉じるタイミングで通知も消す */
    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Live session",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Brings the phone screen to the foreground when a watch session starts."
                setBypassDnd(false)
            },
        )
    }
}
