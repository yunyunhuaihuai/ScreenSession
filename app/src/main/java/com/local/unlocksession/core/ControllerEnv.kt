package com.local.unlocksession.core

import com.local.unlocksession.logic.SessionSnapshot

/**
 * 控制器环境抽象：时钟、设备事实、系统能力、延迟任务与通知出口。
 *
 * 生产实现 [AndroidEnv] 绑定 Android 服务；测试注入 Fake，
 * 使跨会话任务取消（R1）、闹钟失败回传（R7）、提醒规划与复核（R0）等
 * 都能在 JVM 上验证。
 */
interface ControllerEnv {
    // ---- 时钟 ----
    fun nowElapsed(): Long

    // ---- 设备事实 ----
    fun isInteractive(): Boolean
    fun isKeyguardLocked(): Boolean
    fun isDeviceLocked(): Boolean
    fun isKeyguardSecure(): Boolean
    fun isInCall(): Boolean
    fun bootCount(): Int

    // ---- 能力 ----
    fun isAdminActive(): Boolean
    fun canDrawOverlays(): Boolean

    // ---- 系统任务 ----
    /** 排定到期精确闹钟；false = 排定失败（R7 必须如实回传） */
    fun scheduleDeadlineAlarm(sessionId: Long, deadlineElapsed: Long): Boolean
    fun cancelDeadlineAlarm()

    /** 排定提前提醒精确闹钟；false = 排定失败（只影响提醒，不影响锁屏） */
    fun scheduleReminderAlarm(sessionId: Long, thresholdMs: Long, triggerElapsed: Long): Boolean
    /** 取消一批提醒闹钟（按 thresholdMs 定位身份） */
    fun cancelReminderAlarms(thresholdsMs: Collection<Long>)

    /** lockNow()；返回 (已发出, 错误)。不抛异常不代表锁屏完成 */
    fun lockNow(): Pair<Boolean, String?>

    /** 提前熄屏结束时加强 keyguard（best effort，仅在管理员+安全凭据时有效） */
    fun enforceLockOnScreenOff()

    // ---- 延迟任务（带 key，可取消；执行时仍须由控制器做会话校验） ----
    fun postDelayed(key: String, delayMs: Long, block: () -> Unit)
    fun cancelDelayed(key: String)
    fun hasDelayed(key: String): Boolean

    // ---- 服务挂载与启停 ----
    fun attachForegroundService(service: android.app.Service)
    fun detachForegroundService(service: android.app.Service)
    fun startMonitoringService(reason: String)
    fun stopMonitoringService()

    /** 监控前台服务当前是否挂载在本进程（用于恢复协调与诊断，幂等恢复的判据） */
    fun isServiceAttached(): Boolean

    // ---- UI 线程 ----
    fun postUi(block: () -> Unit)

    // ---- UI 出口 ----
    fun showSelectionOverlay(snap: SessionSnapshot)
    fun hideSelectionOverlay()
    fun notifySession(snap: SessionSnapshot, showCountdown: Boolean)
    fun notifyNotReady(reason: String)
    fun cancelNotReady()
    fun notifyReminder(sessionId: Long, thresholdMs: Long)
    fun notifyTestReminder()
    fun cancelReminderNotifications(thresholdsMs: Collection<Long>)
    fun cancelTestReminder()

    /** 通知是否可用（用于测试提醒按钮与提醒投递前提示） */
    fun notificationsEnabled(): Boolean
}

/** 诊断出口抽象（生产=Diagnostics 文件日志；测试=内存记录） */
interface DiagSink {
    fun log(tag: String, message: String)
}
