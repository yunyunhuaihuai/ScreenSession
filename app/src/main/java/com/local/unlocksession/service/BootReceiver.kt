package com.local.unlocksession.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.unlocksession.data.SessionRepository
import com.local.unlocksession.diag.Diagnostics

/**
 * 开机 / 应用更新后恢复监控。
 * 重启后旧会话必须作废（elapsedRealtime 跨重启不可复用），由服务的恢复检查清理；
 * 首次真正解锁后重新选择。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context?.applicationContext ?: return
        val diag = Diagnostics.get(appContext)
        diag.log("BOOT", "$action 收到")
        if (!SessionRepository(appContext).isMonitoringEnabled()) {
            diag.log("BOOT", "$action 收到，但监控已关闭，不启动服务")
            return
        }
        try {
            val service = Intent(appContext, UnlockMonitorService::class.java)
                .putExtra(UnlockMonitorService.EXTRA_REASON, action)
            appContext.startForegroundService(service)
            diag.log("BOOT", "$action → 已请求启动监控服务")
        } catch (e: Exception) {
            diag.log("BOOT", "$action 启动服务失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
