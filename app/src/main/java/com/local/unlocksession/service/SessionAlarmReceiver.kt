package com.local.unlocksession.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.unlocksession.App

/**
 * 到期精确闹钟接收器。
 * Intent extras 不参与 PendingIntent 身份比较，会话标识只用于旧事件校验：
 * 状态机会核对 firedSessionId 与当前会话，旧会话闹钟一律忽略。
 */
class SessionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_SESSION_ALARM) return
        val firedSessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val controller = (context?.applicationContext as? App)?.controller ?: return
        controller.signalAlarm(firedSessionId)
    }

    companion object {
        const val ACTION_SESSION_ALARM = "com.local.unlocksession.ACTION_SESSION_ALARM"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
