package com.local.unlocksession.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.local.unlocksession.data.SessionRepository
import com.local.unlocksession.diag.Diagnostics
import com.local.unlocksession.lock.LockController
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.service.ReminderReceiver
import com.local.unlocksession.service.SessionAlarmReceiver
import com.local.unlocksession.service.UnlockMonitorService
import com.local.unlocksession.overlay.SelectionOverlay
import com.local.unlocksession.session.SessionPanelBridge
import com.local.unlocksession.ui.MainActivity

/**
 * [ControllerEnv] 的生产实现：绑定 Android 系统服务与本项目组件。
 *
 * PendingIntent 身份约定（extras 不参与身份比较）：
 * - 到期任务：action=ACTION_SESSION_ALARM，requestCode 固定 1001（兼容旧版本已排定任务）；
 * - 提醒任务：action=ACTION_SESSION_REMINDER，requestCode = 200000 + thresholdSeconds，
 *   不同时点身份互不覆盖；sessionId 仅作为 extra 参与事件校验。
 */
class AndroidEnv(
    private val appContext: Context,
    private val repo: SessionRepository,
    private val diag: Diagnostics
) : ControllerEnv {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val delayed = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    private val notifier = SessionNotifier(appContext)
    @Volatile private var foregroundService: android.app.Service? = null

    // ---- 时钟 ----
    override fun nowElapsed(): Long = SystemClock.elapsedRealtime()

    // ---- 设备事实 ----
    override fun isInteractive(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    override fun isKeyguardLocked(): Boolean = LockController.isKeyguardLocked(appContext)

    override fun isDeviceLocked(): Boolean = LockController.isDeviceLocked(appContext)

    override fun isKeyguardSecure(): Boolean = LockController.isKeyguardSecure(appContext)

    /** 通话中的接近传感器黑屏不是会话结束信号（无需 READ_PHONE_STATE 的尽力判断） */
    override fun isInCall(): Boolean {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return false
        return try {
            am.mode == android.media.AudioManager.MODE_IN_CALL ||
                am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            false
        }
    }

    override fun bootCount(): Int = Diagnostics.bootCount(appContext)

    // ---- 能力 ----
    override fun isAdminActive(): Boolean = LockController.isAdminActive(appContext)

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    // ---- 系统任务 ----
    override fun scheduleDeadlineAlarm(sessionId: Long, deadlineElapsed: Long): Boolean {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadlineElapsed,
                alarmPendingIntent(sessionId)
            )
            true
        } catch (e: Exception) {
            diag.log("ALARM", "到期闹钟排定失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override fun cancelDeadlineAlarm() {
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPendingIntent(0L))
            diag.log("ALARM", "已取消到期闹钟")
        } catch (e: Exception) {
            diag.log("ALARM", "取消闹钟异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun scheduleReminderAlarm(sessionId: Long, thresholdMs: Long, triggerElapsed: Long): Boolean {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appContext, ReminderReceiver::class.java)
            .setAction(ACTION_SESSION_REMINDER)
            .putExtra(ReminderReceiver.EXTRA_SESSION_ID, sessionId)
            .putExtra(ReminderReceiver.EXTRA_THRESHOLD_MS, thresholdMs)
        val pi = PendingIntent.getBroadcast(
            appContext,
            reminderRequestCode(thresholdMs),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, pi)
            true
        } catch (e: Exception) {
            diag.log("RMD", "提醒闹钟排定失败 threshold=${thresholdMs}ms: ${e.message}")
            false
        }
    }

    override fun cancelReminderAlarms(thresholdsMs: Collection<Long>) {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (t in thresholdsMs) {
            try {
                val intent = Intent(appContext, ReminderReceiver::class.java)
                    .setAction(ACTION_SESSION_REMINDER)
                am.cancel(
                    PendingIntent.getBroadcast(
                        appContext,
                        reminderRequestCode(t),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } catch (e: Exception) {
                diag.log("RMD", "取消提醒闹钟异常 threshold=${t}ms: ${e.message}")
            }
        }
    }

    override fun lockNow(): Pair<Boolean, String?> = LockController.requestLock(appContext, diag)

    override fun enforceLockOnScreenOff() {
        if (!LockController.isAdminActive(appContext)) {
            diag.log("LOCK", "熄屏加强锁定跳过：管理员未启用")
            return
        }
        if (!LockController.isKeyguardSecure(appContext)) {
            diag.log("LOCK", "熄屏加强锁定跳过：系统未设置安全锁屏凭据")
            return
        }
        try {
            val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            dpm.lockNow()
            diag.log("LOCK", "熄屏后已调用 lockNow() 加强 keyguard（防宽限期）")
        } catch (e: Exception) {
            diag.log("LOCK", "熄屏加强锁定异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---- 延迟任务 ----
    override fun postDelayed(key: String, delayMs: Long, block: () -> Unit) {
        cancelDelayed(key)
        val r = Runnable { block() }
        delayed[key] = r
        mainHandler.postDelayed(r, delayMs)
    }

    override fun cancelDelayed(key: String) {
        delayed.remove(key)?.let { mainHandler.removeCallbacks(it) }
    }

    override fun hasDelayed(key: String): Boolean = delayed.containsKey(key)

    // ---- 服务挂载与启停 ----
    override fun attachForegroundService(service: android.app.Service) {
        foregroundService = service
        notifier.ensureChannels()
        notifier.attach(service)
    }

    override fun detachForegroundService(service: android.app.Service) {
        if (foregroundService === service) foregroundService = null
        notifier.detach()
    }

    override fun startMonitoringService(reason: String) {
        try {
            val intent = Intent(appContext, UnlockMonitorService::class.java)
                .putExtra(UnlockMonitorService.EXTRA_REASON, reason)
            appContext.startForegroundService(intent)
        } catch (e: Exception) {
            diag.log("SVC", "启动前台服务失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun stopMonitoringService() {
        try {
            appContext.stopService(Intent(appContext, UnlockMonitorService::class.java))
        } catch (e: Exception) {
            diag.log("SVC", "停止前台服务失败: ${e.message}")
        }
    }

    override fun postUi(block: () -> Unit) {
        mainHandler.post(block)
    }

    // ---- UI 出口 ----
    override fun showSelectionOverlay(snap: SessionSnapshot) {
        SelectionOverlay.show(
            appContext,
            snap.sessionId,
            repo.getQuickMinutes().map { "$it 分钟" },
            repo.isDebugSecondsMode(),
            panelBridgeForOverlay()
        )
        diag.log("OVF", "已请求显示选择层 sessionId=${snap.sessionId}")
    }

    private var overlayBridge: SessionPanelBridge? = null

    private fun panelBridgeForOverlay(): SessionPanelBridge {
        // 控制器持有的桥与悬浮层共享；在 env 构造后由控制器注入
        return overlayBridge ?: throw IllegalStateException("panel bridge 未注入")
    }

    fun setPanelBridge(bridge: SessionPanelBridge) {
        overlayBridge = bridge
    }

    override fun hideSelectionOverlay() {
        SelectionOverlay.hide()
        diag.log("OVF", "已请求移除选择层")
    }

    override fun notifySession(snap: SessionSnapshot, showCountdown: Boolean) {
        notifier.update(snap, showCountdown)
    }

    override fun notifyNotReady(reason: String) {
        notifier.showNotReady(reason)
    }

    override fun cancelNotReady() {
        notifier.cancelNotReady()
    }

    override fun notifyReminder(sessionId: Long, thresholdMs: Long) {
        notifier.showReminder(thresholdMs)
    }

    override fun notifyTestReminder() {
        notifier.showTestReminder()
    }

    override fun cancelReminderNotifications(thresholdsMs: Collection<Long>) {
        for (t in thresholdsMs) notifier.cancelReminder(t)
    }

    override fun cancelTestReminder() {
        notifier.cancelTestReminder()
    }

    override fun notificationsEnabled(): Boolean = notifier.areNotificationsEnabled()

    private fun alarmPendingIntent(sessionId: Long): PendingIntent {
        val intent = Intent(appContext, SessionAlarmReceiver::class.java)
            .setAction(SessionAlarmReceiver.ACTION_SESSION_ALARM)
            .putExtra(SessionAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE_DEADLINE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val REQUEST_CODE_DEADLINE = 1001
        const val ACTION_SESSION_REMINDER = "com.local.unlocksession.ACTION_SESSION_REMINDER"

        /** 提醒 requestCode 基址：thresholdSeconds 最大 86400，远离到期任务的 1001 */
        fun reminderRequestCode(thresholdMs: Long): Int =
            (200000 + thresholdMs / 1000).toInt()
    }
}
