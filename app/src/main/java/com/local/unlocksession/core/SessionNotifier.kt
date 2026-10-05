package com.local.unlocksession.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.local.unlocksession.R
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState
import com.local.unlocksession.ui.MainActivity
import com.local.unlocksession.util.Format

/**
 * 前台服务通知与提醒通知。
 * 计时中的通知只展示剩余时间，不提供任何取消/延长/重置入口。
 */
class SessionNotifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var service: Service? = null
    private var lastNotReadyReason: String? = null

    fun attach(s: Service) {
        service = s
    }

    fun detach() {
        service = null
    }

    fun ensureChannels() {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION, context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.notif_channel_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT, "异常提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "权限缺失、锁屏失败等异常提醒" }
        )
    }

    /** 会话状态通知（前台服务通知或普通常驻通知） */
    fun update(snapshot: SessionSnapshot) {
        val text = textFor(snapshot)
        val notification = build(text, ongoing = true, alert = false)
        val s = service
        if (s != null) {
            try {
                s.startForeground(NOTIF_ID, notification)
            } catch (e: Exception) {
                nm.notify(NOTIF_ID, notification)
            }
        } else {
            nm.notify(NOTIF_ID, notification)
        }
    }

    fun showNotReady(reason: String) {
        if (reason == lastNotReadyReason) {
            // 同一原因节流，不重复弹
            return
        }
        lastNotReadyReason = reason
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIF_ALERT_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle("解锁限时未就绪")
                .setContentText("$reason，请在应用内完成配置")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    fun clearNotReady() {
        lastNotReadyReason = null
    }

    private fun textFor(s: SessionSnapshot): String = when (s.state) {
        SessionState.NO_SESSION -> context.getString(R.string.monitor_waiting)
        SessionState.PENDING_SELECTION -> context.getString(R.string.monitor_pending)
        SessionState.TIMING -> context.getString(
            R.string.monitor_timing,
            Format.remaining(s.deadlineElapsed - android.os.SystemClock.elapsedRealtime())
        )
        SessionState.UNLIMITED -> context.getString(R.string.monitor_unlimited)
        SessionState.LOCK_REQUESTED -> context.getString(R.string.monitor_lock_requested)
        SessionState.LOCK_FAILED -> context.getString(R.string.monitor_lock_failed, s.lockError ?: "")
    }

    private fun build(text: String, ongoing: Boolean, alert: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle("解锁限时")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_ALERT = "alert"
        const val NOTIF_ID = 42
        const val NOTIF_ALERT_ID = 43
    }
}
