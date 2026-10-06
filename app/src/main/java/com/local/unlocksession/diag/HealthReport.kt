package com.local.unlocksession.diag

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.local.unlocksession.core.SessionController
import com.local.unlocksession.core.SessionNotifier
import com.local.unlocksession.lock.LockController
import com.local.unlocksession.logic.SessionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台运行健康报告：只输出本进程可核实的事实，面向维护者诊断，不进入孩子可见 UI。
 *
 * 无法通过可靠接口读取的 ColorOS 选项（自启动、允许后台运行、耗电管理、最近任务
 * 锁定）一律标注"需手动确认"，绝不显示虚假的"已开启"。
 * 电池优化豁免≠免除所有系统限制，更≠获得全部 ColorOS 后台权限。
 */
object HealthReport {

    fun build(context: Context, controller: SessionController): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            appendLine("== 后台运行健康报告 ==")
            appendLine("生成时间: $wall（墙钟）")
            appendLine(Diagnostics.deviceInfoLine(context))
            appendLine("进程已运行: ${humanDuration(SystemClock.elapsedRealtime())}")
            appendLine()

            appendLine("-- 监控与服务 --")
            appendLine("监控开关（应用内）: ${if (controller.isMonitoringEnabledForUi()) "开启" else "关闭"}")
            appendLine("前台服务挂载（本进程内可见）: ${if (controller.isServiceAttached()) "已挂载" else "未挂载"}")
            appendLine("最近服务启动原因: ${controller.lastServiceStartReason ?: "（本次开机尚无记录）"}")
            appendLine("最近恢复检查原因: ${controller.lastRecoverReason ?: "（本次开机尚无记录）"}")
            appendLine("最近有效解锁检测: ${controller.lastUnlockDetected}")
            appendLine()

            appendLine("-- 当前会话 --")
            val s = controller.snapshot
            appendLine("状态: ${s.state}（会话 #${s.sessionId}）")
            if (s.isTiming) {
                val remain = s.deadlineElapsed - SystemClock.elapsedRealtime()
                appendLine("截止(单调时钟): ${s.deadlineElapsed}，剩余 ${humanRemaining(remain)}")
                appendLine("到期闹钟排定: ${if (s.alarmScheduled) "成功" else "失败（进程内兜底检查运行中）"}")
                appendLine("提醒时点快照(秒): ${s.sessionReminderThresholds}")
            }
            if (s.state == SessionState.LOCK_FAILED) appendLine("锁屏失败原因: ${s.lockError}")
            appendLine()

            appendLine("-- 系统权限与状态 --")
            appendLine("设备管理员: ${if (LockController.isAdminActive(context)) "已启用" else "未启用"}")
            appendLine("悬浮窗: ${if (Settings.canDrawOverlays(context)) "已授予" else "未授予"}")
            val batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)
            appendLine("电池优化豁免: ${if (batteryOk) "已列入白名单" else "未豁免（系统可限制后台）"}")
            appendLine("Doze 当前: ${if (pm.isDeviceIdleMode) "处于空闲模式" else "未处于空闲模式"}")
            appendLine("屏幕当前: ${if (pm.isInteractive) "亮屏" else "熄屏"}")
            appendLine("keyguard 当前: ${if (LockController.isKeyguardLocked(context) || LockController.isDeviceLocked(context)) "锁定" else "未锁定"}")
            appendLine("通知总开关: ${if (nm.areNotificationsEnabled()) "开启" else "⚠ 已被系统关闭"}")
            val ch = nm.getNotificationChannel(SessionNotifier.CHANNEL_REMINDER)
            appendLine(
                when {
                    ch == null -> "提醒渠道: 未创建"
                    ch.importance == android.app.NotificationManager.IMPORTANCE_NONE -> "提醒渠道: ⚠ 已被关闭"
                    else -> "提醒渠道: 重要性=${ch.importance} 震动=${ch.shouldVibrate()}"
                }
            )
            appendLine("应用待机桶: ${standbyBucketName(context)}")
            appendLine()

            appendLine("-- ColorOS 选项（无可靠读取接口，需手动确认） --")
            appendLine("自启动管理: 需手动确认（手机管家 → 应用管理 → 解锁限时 → 自启动）")
            appendLine("允许完全后台行为/后台运行: 需手动确认（同上页面 → 耗电管理 → 允许完全后台行为）")
            appendLine("最近任务锁定应用: 需手动确认（最近任务卡片下拉/长按 → 锁定）")
            appendLine("注意: 电池优化豁免不等于获得以上 ColorOS 后台权限，需逐项在系统设置中确认")
            appendLine()

            appendLine("-- 最近任务卡片与进程回收说明 --")
            appendLine("划掉单个最近任务卡片 ≠ 强行停止；START_STICKY 是恢复机制而非即时重启承诺。")
            appendLine("划卡后服务可能被系统保留或稍后重启，以\"前台服务挂载\"一行与诊断日志为准。")
        }
    }

    private fun humanRemaining(ms: Long): String {
        val total = (if (ms < 0) 0 else ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun humanDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "${h}h ${m}m ${s}s"
    }

    private fun standbyBucketName(context: Context): String {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return "无法读取"
            // 无参重载返回本应用自己的桶（API 28+；带包名参数的重载是 API 31 才有）
            when (usm.getAppStandbyBucket()) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE（活跃）"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET（工作集）"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT（常用）"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE（稀有：任务/闹钟受限）"
                else -> "EXCLUSIVE/受限级别较高"
            }
        } catch (e: Exception) {
            "无法读取（${e.javaClass.simpleName}）"
        }
    }

    /** 导出文件名：health_20261006_121530.txt */
    fun exportFileName(): String =
        "health_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
}
