package com.local.unlocksession.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.unlocksession.App

/**
 * 到期精确闹钟接收器。
 *
 * R2：使用 goAsync 保持广播生命周期覆盖异步处理；完成回调在一切路径（含提交失败）执行。
 * Intent extras 不参与 PendingIntent 身份比较，会话标识只用于旧事件校验。
 */
class SessionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_SESSION_ALARM) return
        val app = context?.applicationContext as? App ?: return
        val firedSessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val controller = app.controller
        val pending = goAsync()
        controller.handleAlarmAsync(firedSessionId) { pending.finish() }
    }

    companion object {
        const val ACTION_SESSION_ALARM = "com.local.unlocksession.ACTION_SESSION_ALARM"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
