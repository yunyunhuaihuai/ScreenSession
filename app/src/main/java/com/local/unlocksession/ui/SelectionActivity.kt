package com.local.unlocksession.ui

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.local.unlocksession.App
import com.local.unlocksession.R
import com.local.unlocksession.logic.SessionSnapshot

/**
 * 兜底选择页：仅在悬浮窗权限缺失、无法弹出覆盖层时由“未就绪”通知进入。
 * 选择要求仍然生效：返回键不能解除，选完自动结束。
 * 悬浮窗权限正常时不启动（由覆盖层承担）。
 */
class SelectionActivity : Activity() {

    private val controller get() = (application as App).controller

    private var panel: SessionPanelView? = null
    private var finishedByState = false

    private val snapshotListener = { s: SessionSnapshot ->
        runOnUiThread {
            if (!s.wantsOverlay && !finishedByState) {
                finishedByState = true
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            // 正常路径：覆盖层负责选择，Activity 不需要
            finish()
            return
        }
        val view = SessionPanelView(this)
        view.bind(controller.quickLabels(), controller.isDebugSecondsMode())
        view.listener = controller.panelBridge()
        setContentView(view)
        panel = view
    }

    override fun onResume() {
        super.onResume()
        controller.addUiListener(snapshotListener)
        // 进入时可能已经离开待选择状态
        if (!controller.snapshot.wantsOverlay && !isFinishing) {
            finishedByState = true
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        controller.removeUiListener(snapshotListener)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 吞掉返回键：选择要求不能被返回解除；面板自身的输入模式由其内部处理
    }
}
