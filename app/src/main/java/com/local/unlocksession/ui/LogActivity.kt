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

/** 诊断日志查看（有容量上限的本地记录，可导出） */
class LogActivity : Activity() {

    private val diag get() = (application as App).diagnostics

    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        logText = findViewById(R.id.log_text)
        findViewById<Button>(R.id.log_refresh).setOnClickListener { reload() }
        findViewById<Button>(R.id.log_export).setOnClickListener { export() }
        findViewById<Button>(R.id.log_copy).setOnClickListener { copy() }
        reload()
    }

    private fun reload() {
        logText.text = diag.readLog()
        findViewById<ScrollView>(R.id.log_scroll).post {
            findViewById<ScrollView>(R.id.log_scroll).fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun export() {
        try {
            val file = diag.logFile()
            if (!file.exists()) {
                Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", diag.readLog()))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
