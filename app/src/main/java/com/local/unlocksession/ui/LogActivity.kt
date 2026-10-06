package com.local.unlocksession.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.local.unlocksession.App
import com.local.unlocksession.R
import com.local.unlocksession.diag.HealthReport
import java.io.File

/**
 * 诊断页（面向维护者）：上半屏为后台运行健康信息（可核实的服务/权限/闹钟/电池状态，
 * ColorOS 专有选项标注"需手动确认"），下半屏为原始诊断日志。
 * 支持一键导出（健康报告 + 日志合并为单个文本，经系统分享发出）。
 */
class LogActivity : Activity() {

    private val diag get() = (application as App).diagnostics
    private val controller get() = (application as App).controller

    private lateinit var logText: TextView
    private lateinit var healthText: TextView
    private lateinit var healthScroll: ScrollView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        logText = findViewById(R.id.log_text)
        healthText = findViewById(R.id.health_text)
        healthScroll = findViewById(R.id.health_scroll)
        logScroll = findViewById(R.id.log_scroll)
        findViewById<Button>(R.id.log_refresh).setOnClickListener { reloadLog() }
        findViewById<Button>(R.id.log_export).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.log_copy).setOnClickListener { copy() }
        findViewById<Button>(R.id.health_refresh).setOnClickListener { reloadHealth(true) }
        findViewById<Button>(R.id.health_export).setOnClickListener { exportHealthBundle() }
        reloadHealth(false)
        reloadLog()
    }

    override fun onResume() {
        super.onResume()
        reloadHealth(false)
        reloadLog()
    }

    private fun reloadHealth(scrollToTop: Boolean) {
        healthText.text = try {
            HealthReport.build(this, controller)
        } catch (e: Exception) {
            "健康信息生成失败: ${e.javaClass.simpleName}: ${e.message}"
        }
        if (scrollToTop) healthScroll.fullScroll(ScrollView.FOCUS_UP)
    }

    private fun reloadLog() {
        logText.text = diag.readLog()
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 一键诊断导出：健康报告 + 完整日志合并成单个文本后走系统分享 */
    private fun exportHealthBundle() {
        try {
            val out = File(diag.logFile().parentFile, HealthReport.exportFileName())
            val content = buildString {
                append(HealthReport.build(this@LogActivity, controller))
                appendLine()
                appendLine("== 诊断日志 ==")
                append(diag.readLog())
            }
            out.writeText(content, Charsets.UTF_8)
            shareFile(out, "导出一键诊断（健康+日志）", getString(R.string.log_export_subject))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportLog() {
        try {
            val file = diag.logFile()
            if (!file.exists()) {
                Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
                return
            }
            shareFile(file, "导出诊断日志", getString(R.string.log_export_subject))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File, title: String, subject: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, title))
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", diag.readLog()))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
