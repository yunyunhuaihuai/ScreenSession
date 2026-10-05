package com.local.unlocksession.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.local.unlocksession.R

/**
 * 时长选择面板：悬浮层与兜底 Activity 共用。
 *
 * - 固定包含三个快捷时长（文字随配置变化）、“不限”与“自定义”；
 * - 自定义输入非法时就地提示，不产生事件；
 * - 返回键：输入模式下退出输入，面板模式直接吞掉——选择要求不能被返回键解除。
 */
class SessionPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onQuickSelected(index: Int)
        fun onUnlimitedSelected()
        /** 提交自定义输入；返回 false 表示输入非法，面板就地提示 */
        fun onCustomSubmitted(raw: String): Boolean
        fun onCustomCancelled()
    }

    var listener: Listener? = null

    private val quickButtons: List<Button>
    private val btnUnlimited: Button
    private val btnCustom: Button
    private val customRow: LinearLayout
    private val customInput: EditText
    private val customError: TextView
    private val btnCustomOk: Button
    private val btnCustomBack: Button

    private var secondsMode = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_session_panel, this, true)
        quickButtons = listOf(
            findViewById(R.id.btn_quick0),
            findViewById(R.id.btn_quick1),
            findViewById(R.id.btn_quick2)
        )
        btnUnlimited = findViewById(R.id.btn_unlimited)
        btnCustom = findViewById(R.id.btn_custom)
        customRow = findViewById(R.id.custom_row)
        customInput = findViewById(R.id.et_custom)
        customError = findViewById(R.id.custom_error)
        btnCustomOk = findViewById(R.id.btn_custom_ok)
        btnCustomBack = findViewById(R.id.btn_custom_back)

        quickButtons.forEachIndexed { i, b ->
            b.setOnClickListener { listener?.onQuickSelected(i) }
        }
        btnUnlimited.setOnClickListener { listener?.onUnlimitedSelected() }
        btnCustom.setOnClickListener { showCustom(true) }
        btnCustomBack.setOnClickListener {
            listener?.onCustomCancelled()
            showCustom(false)
        }
        btnCustomOk.setOnClickListener { submitCustom() }

        btnUnlimited.text = context.getString(R.string.sel_unlimited)
        btnCustom.text = context.getString(R.string.sel_custom)
        btnCustomOk.text = context.getString(R.string.sel_custom_ok)
        btnCustomBack.text = context.getString(R.string.sel_custom_back)

        // 返回键处理：吞掉 BACK，不允许解除选择要求
        isFocusableInTouchMode = true
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (customRow.visibility == VISIBLE) {
                    listener?.onCustomCancelled()
                    showCustom(false)
                }
                true
            } else {
                false
            }
        }
    }

    /** 绑定快捷时长文案与自定义输入模式（文字随配置变化，非常量） */
    fun bind(quickLabels: List<String>, debugSecondsMode: Boolean) {
        secondsMode = debugSecondsMode
        quickButtons.forEachIndexed { i, b ->
            b.text = quickLabels.getOrNull(i) ?: context.getString(R.string.minutes_fmt, 10 * (i + 1))
        }
        customInput.hint = context.getString(
            if (secondsMode) R.string.sel_custom_debug_hint else R.string.sel_custom_hint
        )
    }

    fun showCustom(show: Boolean) {
        if (show) {
            customRow.visibility = VISIBLE
            quickButtons.forEach { it.visibility = GONE }
            btnUnlimited.visibility = GONE
            btnCustom.visibility = GONE
            customError.visibility = GONE
            customInput.setText("")
            customInput.requestFocus()
        } else {
            customRow.visibility = GONE
            quickButtons.forEach { it.visibility = VISIBLE }
            btnUnlimited.visibility = VISIBLE
            btnCustom.visibility = VISIBLE
            customInput.setText("")
            customError.visibility = GONE
        }
    }

    private fun submitCustom() {
        val raw = customInput.text?.toString() ?: ""
        val accepted = listener?.onCustomSubmitted(raw) ?: false
        if (accepted) {
            customError.visibility = GONE
        } else {
            customError.text = context.getString(
                if (secondsMode) R.string.sel_custom_invalid_seconds else R.string.sel_custom_invalid
            )
            customError.visibility = VISIBLE
        }
    }
}
