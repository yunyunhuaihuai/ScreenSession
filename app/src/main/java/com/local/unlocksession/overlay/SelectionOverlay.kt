package com.local.unlocksession.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.local.unlocksession.R
import com.local.unlocksession.ui.SessionPanelView

/**
 * 待选择悬浮层：获授权的 TYPE_APPLICATION_OVERLAY 全屏窗口。
 *
 * - 必须选择：全屏遮罩吞掉触摸，Home / 最近任务不会解除选择要求；
 * - 可输入：窗口可聚焦，自定义时长由窗口内 EditText 完成（软键盘可弹出）；
 * - 防重复：同一会话重复 show 为幂等操作；会话结束统一 hide；
 * - 锁屏期间不显示（状态机在熄屏时发出 Hide 动作）。
 */
object SelectionOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var added: SessionPanelView? = null
    private var addedPendingId: Long = 0L

    fun isShowing(): Boolean = added != null

    /** 必须在主线程调用（SessionController 已保证 post 到主线程） */
    fun show(
        context: Context,
        pendingSessionId: Long,
        quickLabels: List<String>,
        debugSecondsMode: Boolean,
        listener: SessionPanelView.Listener
    ) {
        mainHandler.post { showInternal(context, pendingSessionId, quickLabels, debugSecondsMode, listener) }
    }

    fun hide() {
        mainHandler.post { hideInternal() }
    }

    private fun showInternal(
        context: Context,
        pendingSessionId: Long,
        quickLabels: List<String>,
        debugSecondsMode: Boolean,
        listener: SessionPanelView.Listener
    ) {
        if (added != null) {
            if (addedPendingId == pendingSessionId) return
            // 新会话进入待选择：先撤旧窗再挂新窗
            hideInternal()
        }
        val themed = ContextThemeWrapper(context.applicationContext, R.style.Theme_UnlockSession)
        val panel = SessionPanelView(themed)
        panel.bind(quickLabels, debugSecondsMode)
        panel.listener = listener

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        try {
            val wm = themed.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(panel, params)
            added = panel
            addedPendingId = pendingSessionId
            // 请求焦点以便接收返回键
            panel.post { panel.requestFocus() }
            com.local.unlocksession.diag.Diagnostics.get(themed)
                .log("OVF", "悬浮选择层已挂载 sessionId=$pendingSessionId")
        } catch (e: Exception) {
            added = null
            com.local.unlocksession.diag.Diagnostics.get(themed)
                .log("OVF", "悬浮选择层挂载失败: ${e.javaClass.simpleName}: ${e.message}")
            android.util.Log.w("SelectionOverlay", "添加悬浮窗失败", e)
        }
    }

    private fun hideInternal() {
        val panel = added ?: return
        added = null
        try {
            val wm = panel.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(panel)
            com.local.unlocksession.diag.Diagnostics.get(panel.context)
                .log("OVF", "悬浮选择层已移除")
        } catch (e: Exception) {
            android.util.Log.w("SelectionOverlay", "移除悬浮窗失败", e)
        }
    }
}
