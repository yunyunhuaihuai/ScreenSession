package com.local.unlocksession.diag

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 有容量上限的本地诊断日志。
 *
 * 记录：应用版本、关键事件、会话标识、前后状态、单调时钟时间、截止时间、
 * 设备交互／锁屏状态、管理员状态、锁屏请求和恢复原因。
 * 不记录系统密码、其他应用内容或个人数据。
 */
class Diagnostics private constructor(private val logDir: File) : com.local.unlocksession.core.DiagSink {

    private val lock = Any()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    constructor(context: Context) : this(
        File(context.filesDir, "diag").apply { mkdirs() }
    )

    override fun log(tag: String, message: String) {
        val line = buildString {
            append(timeFmt.format(Date()))
            append('|').append(SystemClock.elapsedRealtime())
            append("|boot=").append(bootCountValue)
            append("|v").append(VERSION_NAME)
            append('|').append(tag)
            append('|').append(message.replace('\n', ' '))
            append('\n')
        }
        synchronized(lock) {
            try {
                FileOutputStream(logFile(), true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
                trimIfNeeded(logFile())
            } catch (e: Exception) {
                android.util.Log.w(TAG, "诊断日志写入失败", e)
            }
        }
    }

    fun logFile(): File = File(logDir, "diag.log")

    fun readLog(): String = synchronized(lock) {
        val f = logFile()
        if (!f.exists()) return "(暂无日志)"
        try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "(读取失败: ${e.message})"
        }
    }

    /** 日志行内直接可用的 boot 计数（attach 时由控制器注入） */
    @Volatile
    var bootCountValue: Int = -1
        private set

    fun attachContext(context: Context) {
        bootCountValue = bootCount(context)
    }

    /** 超过 256KB 时只保留最后 128KB */
    private fun trimIfNeeded(f: File) {
        if (f.length() <= MAX_BYTES.toLong()) return
        val bytes = f.readBytes()
        if (bytes.size <= KEEP_BYTES) return
        val keep = bytes.copyOfRange(bytes.size - KEEP_BYTES, bytes.size)
        // 对齐到下一行开头，避免半行
        var start = 0
        while (start < keep.size && keep[start] != '\n'.code.toByte()) start++
        f.writeBytes(if (start < keep.size - 1) keep.copyOfRange(start + 1, keep.size) else keep)
    }

    companion object {
        private const val TAG = "Diagnostics"
        private const val MAX_BYTES = 256 * 1024
        private const val KEEP_BYTES = 128 * 1024

        /** 版本串（避免诊断类依赖 BuildConfig 生成时序） */
        const val VERSION_NAME = "0.2.0"

        /** 当前设备的 boot 计数，恢复检查用它区分“重启”与“同次开机进程恢复” */
        fun bootCount(context: Context): Int = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            -1
        }

        @Volatile
        private var instance: Diagnostics? = null

        fun get(context: Context): Diagnostics =
            instance ?: synchronized(this) {
                instance ?: Diagnostics(context.applicationContext).also { instance = it }
            }

        fun deviceInfoLine(context: Context): String =
            "device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} boot=${bootCount(context)} brand=${Build.BRAND}"
    }
}
