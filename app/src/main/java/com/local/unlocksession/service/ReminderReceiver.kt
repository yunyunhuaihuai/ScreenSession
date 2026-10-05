package com.local.unlocksession.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.unlocksession.App

/**
 * 提前提醒闹钟接收器（R2：goAsync + 冷启动核对 + 迟到窗口 + 消费标记先行）。
 * 会话标识与时点作为 extra 参与校验；身份由 action + requestCode（threshold 秒）保证。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_SESSION_REMINDER) return
        val app = context?.applicationContext as? App ?: return
        val firedSessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val thresholdMs = intent.getLongExtra(EXTRA_THRESHOLD_MS, -1L)
        if (firedSessionId <= 0 || thresholdMs <= 0) return
        val controller = app.controller
        val pending = goAsync()
        controller.handleReminderAsync(firedSessionId, thresholdMs) { pending.finish() }
    }

    companion object {
        const val ACTION_SESSION_REMINDER = "com.local.unlocksession.ACTION_SESSION_REMINDER"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_THRESHOLD_MS = "threshold_ms"
    }
}
