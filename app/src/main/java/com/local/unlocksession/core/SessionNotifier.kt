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
import com.local.unlocksession.logic.ReminderPlanner
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState
import com.local.unlocksession.ui.MainActivity
import com.local.unlocksession.util.Format

/**
 * 通知器：常驻前台服务通知（可选倒计时显示）、提前提醒 heads-up、异常提醒、测试提醒。
 *
 * - 前台化只在 attach 时执行一次 startForeground；此后更新一律用同一 ID 的
 *   NotificationManager.notify（服务保持前台，不每秒重复 startForeground）；
 * - 倒计时开关只控制显示：关闭时保留简短的服务运行通知，不停止计时；
 * - 提前提醒使用独立高重要性 channel（session_reminder，震动、无声音），
 *   每个时点独立通知 ID，不使用 onlyAlertOnce 抑制第二次提示。
 */
class SessionNotifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var service: Service? = null
    private var foregroundStarted = false
    private var lastNotReadyReason: String? = null

    fun attach(s: Service) {
        service = s
    }

    fun detach() {
        service = null
        foregroundStarted = false
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
        // 提前提醒独立 channel：高重要性 + 短震动 + 无声音。
        // Android 8+ 创建后行为由系统/用户控制，重复创建同 ID 不会覆盖用户设置。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER, "锁屏提前提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "会话到期前的横幅提醒与短震动"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 120, 200)
                setSound(null, null)
            }
        )
    }

    fun areNotificationsEnabled(): Boolean = nm.areNotificationsEnabled()

    /** 会话状态通知。前台化失败必须如实记录，不能被普通 notify 成功掩盖 */
    fun update(snapshot: SessionSnapshot, showCountdown: Boolean) {
        val notification = build(snapshot, showCountdown)
        val s = service
        if (s != null && !foregroundStarted) {
            try {
                s.startForeground(NOTIF_FGS_ID, notification)
                foregroundStarted = true
                return
            } catch (e: Exception) {
                android.util.Log.w("SessionNotifier", "startForeground 失败", e)
                com.local.unlocksession.diag.Diagnostics.get(context)
                    .log("NTF", "startForeground 失败: ${e.javaClass.simpleName}: ${e.message}（降级为普通通知）")
                // 落入普通通知路径
            }
        }
        nm.notify(NOTIF_FGS_ID, notification)
    }

    fun showNotReady(reason: String) {
        if (reason == lastNotReadyReason) return
        lastNotReadyReason = reason
        nm.notify(
            NOTIF_ALERT_ID,
            builder(CHANNEL_ALERT)
                .setContentTitle("解锁限时未就绪")
                .setContentText("$reason，请在应用内完成配置")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("$reason，请在应用内完成配置"))
                .setAutoCancel(true)
                .build()
        )
    }

    fun cancelNotReady() {
        lastNotReadyReason = null
        nm.cancel(NOTIF_ALERT_ID)
    }

    /** 提前提醒 heads-up：每时点独立 ID，第二次提醒同样有横幅机会 */
    fun showReminder(thresholdMs: Long) {
        val seconds = thresholdMs / 1000
        val text = "还有 ${ReminderPlanner.humanize(seconds)}将锁屏"
        nm.notify(
            NOTIF_REMINDER_BASE + (thresholdMs / 1000).toInt(),
            builder(CHANNEL_REMINDER)
                .setContentTitle("即将锁屏")
                .setContentText(text)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
        )
    }

    fun cancelReminder(thresholdMs: Long) {
        nm.cancel(NOTIF_REMINDER_BASE + (thresholdMs / 1000).toInt())
    }

    /** 测试提醒：真实 channel + 相同构建路径，独立 ID，不覆盖真实通知 */
    fun showTestReminder() {
        nm.notify(
            NOTIF_TEST_ID,
            builder(CHANNEL_REMINDER)
                .setContentTitle("【测试】即将锁屏")
                .setContentText("这是提前提醒的演示（真实提醒会显示剩余时点）")
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
        )
    }

    fun cancelTestReminder() {
        nm.cancel(NOTIF_TEST_ID)
    }

    // ------------------------------------------------------------------

    private fun builder(channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

    private fun textFor(s: SessionSnapshot, showCountdown: Boolean): String = when (s.state) {
        SessionState.NO_SESSION -> context.getString(R.string.monitor_waiting)
        SessionState.PENDING_SELECTION -> context.getString(R.string.monitor_pending)
        SessionState.TIMING -> if (showCountdown) {
            context.getString(
                R.string.monitor_timing,
                Format.remaining(s.deadlineElapsed - android.os.SystemClock.elapsedRealtime())
            )
        } else {
            context.getString(R.string.monitor_timing_hidden)
        }
        SessionState.UNLIMITED -> context.getString(R.string.monitor_unlimited)
        SessionState.LOCK_REQUESTED -> context.getString(R.string.monitor_lock_requested)
        SessionState.LOCK_FAILED -> context.getString(R.string.monitor_lock_failed, s.lockError ?: "")
    }

    private fun build(s: SessionSnapshot, showCountdown: Boolean): Notification {
        val b = builder(CHANNEL_SESSION)
            .setContentTitle("解锁限时")
            .setContentText(textFor(s, showCountdown))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (s.isTiming && !s.alarmScheduled) {
            b.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(textFor(s, showCountdown) + "｜⚠ 到期闹钟设置失败，将使用应用内备用检查")
            )
        }
        return b.build()
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_ALERT = "alert"
        const val CHANNEL_REMINDER = "session_reminder"

        const val NOTIF_FGS_ID = 42
        const val NOTIF_ALERT_ID = 43
        const val NOTIF_TEST_ID = 44
        const val NOTIF_REMINDER_BASE = 5000
    }
}
