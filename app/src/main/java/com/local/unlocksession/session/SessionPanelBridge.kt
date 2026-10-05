package com.local.unlocksession.session

import com.local.unlocksession.core.SessionController
import com.local.unlocksession.logic.DurationInput
import com.local.unlocksession.ui.SessionPanelView

/**
 * 选择面板回调 → 控制器事件的桥接，悬浮层与兜底 Activity 共用。
 * 输入校验失败时返回 false，由面板就地显示错误，不产生事件。
 */
class SessionPanelBridge(private val controller: SessionController) : SessionPanelView.Listener {

    override fun onQuickSelected(index: Int) {
        val minutes = controller.quickMinutes().getOrNull(index) ?: return
        controller.submitSelection(
            controller.snapshot.sessionId,
            unlimited = false,
            durationMs = minutes * 60_000L
        )
    }

    override fun onUnlimitedSelected() {
        controller.submitSelection(
            controller.snapshot.sessionId,
            unlimited = true,
            durationMs = null
        )
    }

    override fun onCustomSubmitted(raw: String): Boolean {
        val ms = if (controller.isDebugSecondsMode()) {
            DurationInput.parseSecondsToMs(raw)
        } else {
            DurationInput.parseMinutesToMs(raw)
        } ?: return false
        controller.submitSelection(controller.snapshot.sessionId, unlimited = false, durationMs = ms)
        return true
    }

    override fun onCustomCancelled() = Unit
}
