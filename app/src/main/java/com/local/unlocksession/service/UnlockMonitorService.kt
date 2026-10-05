package com.local.unlocksession.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.local.unlocksession.App
import com.local.unlocksession.core.SessionController

/**
 * 解锁监控前台服务：动态注册 SCREEN_ON / SCREEN_OFF / USER_PRESENT，
 * 把广播信号转交给 SessionController 串行处理。
 *
 * 不把 ACTION_SCREEN_ON 当成解锁；USER_PRESENT（keyguard 消失）才是创建会话的唯一入口。
 * 服务可能在首次解锁广播之后才启动，因此 onStartCommand 必须执行恢复检查。
 */
class UnlockMonitorService : android.app.Service() {

    private val controller: SessionController
        get() = (application as App).controller

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> controller.signalScreenOff()
                Intent.ACTION_SCREEN_ON -> controller.signalScreenOn()
                Intent.ACTION_USER_PRESENT -> controller.signalUserPresent()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller.attachService(this)
        controller.refreshServiceNotification()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        com.local.unlocksession.diag.Diagnostics.get(this)
            .log("SVC", "UnlockMonitorService onCreate，已注册屏幕/解锁广播")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.refreshServiceNotification()
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "STICKY 重启/进程恢复"
        controller.recover(reason)
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // 未注册时忽略
        }
        controller.detachService(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    companion object {
        const val EXTRA_REASON = "reason"
    }
}
