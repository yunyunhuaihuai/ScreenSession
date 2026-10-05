package com.local.unlocksession.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.local.unlocksession.App
import com.local.unlocksession.BuildConfig
import com.local.unlocksession.R
import com.local.unlocksession.lock.LockController
import com.local.unlocksession.logic.DurationInput
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState

/**
 * 主界面：状态展示、首次授权集中处理、快捷时长编辑、诊断入口。
 *
 * 会话进行中不提供任何停止/重置/延长入口——监控开关同样被锁定，
 * 防止通过普通设置间接取消本次计时。
 */
class MainActivity : Activity() {

    private val controller get() = (application as App).controller
    private val diag get() = (application as App).diagnostics

    private lateinit var statusText: TextView
    private lateinit var warningText: TextView
    private lateinit var adminText: TextView
    private lateinit var adminBtn: Button
    private lateinit var overlayText: TextView
    private lateinit var overlayBtn: Button
    private lateinit var batteryText: TextView
    private lateinit var batteryBtn: Button
    private lateinit var autostartBtn: Button
    private lateinit var monitorSwitch: Switch
    private lateinit var monitorHint: TextView
    private lateinit var quickEdits: List<EditText>
    private lateinit var quickSaveBtn: Button
    private lateinit var debugSecondsSwitch: Switch
    private lateinit var debugCard: View
    private lateinit var versionText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            renderStatus(controller.snapshot)
            if (controller.snapshot.isTiming) uiHandler.postDelayed(this, 1000)
        }
    }

    private val snapshotListener = { _: SessionSnapshot ->
        runOnUiThread {
            renderStatus(controller.snapshot)
            renderPermissions()
            renderMonitorSwitch()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        warningText = findViewById(R.id.warning_text)
        adminText = findViewById(R.id.perm_admin_text)
        adminBtn = findViewById(R.id.perm_admin_btn)
        overlayText = findViewById(R.id.perm_overlay_text)
        overlayBtn = findViewById(R.id.perm_overlay_btn)
        batteryText = findViewById(R.id.perm_battery_text)
        batteryBtn = findViewById(R.id.perm_battery_btn)
        autostartBtn = findViewById(R.id.perm_autostart_btn)
        monitorSwitch = findViewById(R.id.monitor_switch)
        monitorHint = findViewById(R.id.monitor_hint)
        quickEdits = listOf(
            findViewById(R.id.quick_edit0),
            findViewById(R.id.quick_edit1),
            findViewById(R.id.quick_edit2)
        )
        quickSaveBtn = findViewById(R.id.quick_save_btn)
        debugCard = findViewById(R.id.debug_card)
        debugSecondsSwitch = findViewById(R.id.debug_seconds_switch)
        versionText = findViewById(R.id.version_text)

        adminBtn.setOnClickListener {
            try {
                startActivity(LockController.adminActivationIntent(this))
            } catch (e: Exception) {
                toast("无法打开管理员激活页面：${e.message}")
            }
        }
        overlayBtn.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                toast("无法打开悬浮窗授权页面：${e.message}")
            }
        }
        batteryBtn.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                toast("无法打开电池优化设置：${e.message}")
            }
        }
        autostartBtn.setOnClickListener { showColorOSGuide() }

        monitorSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == controller.isMonitoringEnabledForUi()) return@setOnCheckedChangeListener
            val ok = controller.setMonitoring(checked)
            if (!ok) {
                toast("会话进行中不能关闭监控")
                monitorSwitch.isChecked = true
            } else if (checked) {
                toast("监控已开启")
            }
        }

        quickSaveBtn.setOnClickListener { saveQuickMinutes() }

        findViewById<Button>(R.id.diag_view_btn).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.diag_export_btn).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.diag_recover_btn).setOnClickListener {
            if (controller.isMonitoringEnabledForUi()) controller.startMonitoringService()
            toast("已请求自检恢复，见日志")
        }

        debugSecondsSwitch.setOnCheckedChangeListener { _, checked ->
            controller.setDebugSecondsMode(checked)
            toast(if (checked) "自定义输入按秒计" else "自定义输入按分钟计")
        }
        findViewById<Button>(R.id.debug_replay_current_btn).setOnClickListener {
            val id = controller.snapshot.sessionId
            if (id == 0L) toast("当前无会话") else controller.debugReplayAlarm(id)
        }
        findViewById<Button>(R.id.debug_replay_prev_btn).setOnClickListener {
            val id = controller.previousSessionId
            if (id == 0L) toast("无旧会话可重放") else controller.debugReplayAlarm(id)
        }
        findViewById<Button>(R.id.debug_recover_btn).setOnClickListener { controller.debugRecover() }

        versionText.text = getString(
            R.string.version_fmt,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
    }

    override fun onResume() {
        super.onResume()
        if (controller.isMonitoringEnabledForUi()) controller.startMonitoringService()
        controller.addUiListener(snapshotListener)
        debugCard.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        debugSecondsSwitch.isChecked = controller.isDebugSecondsMode()
        renderStatus(controller.snapshot)
        renderPermissions()
        renderMonitorSwitch()
        if (controller.snapshot.isTiming) uiHandler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        controller.removeUiListener(snapshotListener)
        uiHandler.removeCallbacks(tickRunnable)
    }

    // ------------------------------------------------------------------

    private fun renderStatus(s: SessionSnapshot) {
        val label = when (s.state) {
            SessionState.NO_SESSION -> getString(R.string.state_no_session)
            SessionState.PENDING_SELECTION -> getString(R.string.state_pending)
            SessionState.TIMING -> getString(R.string.state_timing)
            SessionState.UNLIMITED -> getString(R.string.state_unlimited)
            SessionState.LOCK_REQUESTED -> getString(R.string.state_lock_requested)
            SessionState.LOCK_FAILED -> getString(R.string.state_lock_failed)
        }
        val remain = if (s.isTiming) {
            val ms = s.deadlineElapsed - android.os.SystemClock.elapsedRealtime()
            "，剩余 ${com.local.unlocksession.util.Format.remaining(ms)}"
        } else ""
        statusText.text = "状态：$label（会话 #${s.sessionId}）$remain" +
            (s.lockError?.let { "，错误：$it" } ?: "")

        warningText.visibility =
            if (LockController.isKeyguardSecure(this)) View.GONE else View.VISIBLE
    }

    private fun renderPermissions() {
        val adminOk = LockController.isAdminActive(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        adminText.text = getString(
            if (adminOk) R.string.perm_admin_ok else R.string.perm_admin_missing
        )
        adminText.setTextColor(getColor(if (adminOk) R.color.ok else R.color.warn))
        adminBtn.visibility = if (adminOk) View.GONE else View.VISIBLE

        overlayText.text = getString(
            if (overlayOk) R.string.perm_overlay_ok else R.string.perm_overlay_missing
        )
        overlayText.setTextColor(getColor(if (overlayOk) R.color.ok else R.color.warn))
        overlayBtn.visibility = if (overlayOk) View.GONE else View.VISIBLE

        batteryText.text = getString(
            if (batteryOk) R.string.perm_battery_ok else R.string.perm_battery_missing
        )
        batteryText.setTextColor(getColor(if (batteryOk) R.color.ok else R.color.warn))
        batteryBtn.visibility = if (batteryOk) View.GONE else View.VISIBLE
    }

    private fun renderMonitorSwitch() {
        val enabled = controller.isMonitoringEnabledForUi()
        val busy = controller.snapshot.isActive || controller.snapshot.wantsOverlay
        monitorSwitch.setOnCheckedChangeListener(null)
        monitorSwitch.isChecked = enabled
        monitorSwitch.isEnabled = !busy
        monitorSwitch.setOnCheckedChangeListener { _, checked ->
            val ok = controller.setMonitoring(checked)
            if (!ok) {
                toast("会话进行中不能关闭监控")
                monitorSwitch.isChecked = true
            }
        }
        monitorHint.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun saveQuickMinutes() {
        val values = quickEdits.map { it.text?.toString()?.trim()?.toIntOrNull() ?: -1 }
        if (values.any { it < 1 || it > 1440 }) {
            toast("请输入 1–1440 之间的整数分钟")
            return
        }
        if (controller.setQuickMinutesPublic(values)) {
            toast("已保存：${values.joinToString(" / ")} 分钟（影响之后的新会话）")
        } else {
            toast("保存失败")
        }
    }

    private fun showColorOSGuide() {
        val components = listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity"
        )
        for (c in components) {
            try {
                val cn = android.content.ComponentName.unflattenFromString(c)!!
                startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // 尝试下一个组件
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.coloros_guide_title)
            .setMessage(R.string.coloros_guide_text)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun exportLog() {
        try {
            val file = diag.logFile()
            if (!file.exists()) {
                toast("暂无日志可导出")
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_export_subject))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出诊断日志"))
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
