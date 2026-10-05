package com.local.unlocksession.data

import android.content.Context
import android.content.SharedPreferences
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState

/**
 * 会话状态与配置的持久化。
 *
 * 会话快照（状态、会话标识、截止时间、恢复信息）与配置（快捷时长、监控开关）
 * 都存于 SharedPreferences，保证进程回收 / 重启后状态可恢复。
 * 截止时间使用 elapsedRealtime 毫秒值，必须与保存时的 boot 归属一起校验，
 * 严禁跨重启复用。
 */
class SessionRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------- 会话快照 ----------------

    fun loadSnapshot(): SessionSnapshot = SessionSnapshot(
        state = enumValueOrDefault(prefs.getString(KEY_STATE, null), SessionState.NO_SESSION),
        sessionId = prefs.getLong(KEY_SESSION_ID, 0L),
        startedElapsed = prefs.getLong(KEY_STARTED_ELAPSED, 0L),
        deadlineElapsed = prefs.getLong(KEY_DEADLINE_ELAPSED, 0L),
        lockError = prefs.getString(KEY_LOCK_ERROR, null)
    )

    fun saveSnapshot(s: SessionSnapshot) {
        prefs.edit()
            .putString(KEY_STATE, s.state.name)
            .putLong(KEY_SESSION_ID, s.sessionId)
            .putLong(KEY_STARTED_ELAPSED, s.startedElapsed)
            .putLong(KEY_DEADLINE_ELAPSED, s.deadlineElapsed)
            .putString(KEY_LOCK_ERROR, s.lockError)
            // 保存时刻的恢复信息：用于判断同次开机
            .putLong(KEY_SAVED_ELAPSED, android.os.SystemClock.elapsedRealtime())
            .putInt(KEY_BOOT_AT_SAVE, bootAtSave)
            .apply()
    }

    /** 保存时由控制器注入的当前 boot 计数 */
    @Volatile
    var bootAtSave: Int = -1
        private set

    fun setBootAtSave(value: Int) {
        bootAtSave = value
    }

    /** 上次保存时的 boot 计数（读取自持久化） */
    fun savedBootCount(): Int = prefs.getInt(KEY_BOOT_AT_SAVE, -1)

    /** 上次保存时刻的 elapsedRealtime（辅助校验单调性） */
    fun savedElapsed(): Long = prefs.getLong(KEY_SAVED_ELAPSED, 0L)

    // ---------------- 会话标识 ----------------

    /** 下一个可用会话标识（预览，不递增） */
    fun peekNextSessionId(): Long = prefs.getLong(KEY_NEXT_SESSION_ID, 1L)

    /** 状态机实际使用了该标识后提交递增 */
    fun commitSessionId(used: Long) {
        if (used >= peekNextSessionId()) {
            prefs.edit().putLong(KEY_NEXT_SESSION_ID, used + 1).apply()
        }
    }

    // ---------------- 配置 ----------------

    fun isMonitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITORING, true)

    fun setMonitoringEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MONITORING, value).apply()
    }

    /** 三个快捷时长（分钟），初始 10/20/30，可编辑持久保存 */
    fun getQuickMinutes(): List<Int> {
        val raw = prefs.getString(KEY_QUICK_MINUTES, null) ?: return DEFAULT_QUICK_MINUTES.toList()
        val parsed = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parsed.size != 3 || parsed.any { it < 1 || it > 1440 }) return DEFAULT_QUICK_MINUTES.toList()
        return parsed
    }

    fun setQuickMinutes(values: List<Int>): Boolean {
        if (values.size != 3 || values.any { it < 1 || it > 1440 }) return false
        prefs.edit().putString(KEY_QUICK_MINUTES, values.joinToString(",")).apply()
        return true
    }

    /** debug 构建专用：自定义时长按秒输入 */
    fun isDebugSecondsMode(): Boolean = prefs.getBoolean(KEY_DEBUG_SECONDS, false)

    fun setDebugSecondsMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_SECONDS, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "unlock_session"
        private const val KEY_STATE = "state"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_STARTED_ELAPSED = "started_elapsed"
        private const val KEY_DEADLINE_ELAPSED = "deadline_elapsed"
        private const val KEY_LOCK_ERROR = "lock_error"
        private const val KEY_SAVED_ELAPSED = "saved_elapsed"
        private const val KEY_BOOT_AT_SAVE = "boot_at_save"
        private const val KEY_NEXT_SESSION_ID = "next_session_id"
        private const val KEY_MONITORING = "monitoring_enabled"
        private const val KEY_QUICK_MINUTES = "quick_minutes"
        private const val KEY_DEBUG_SECONDS = "debug_seconds_mode"

        val DEFAULT_QUICK_MINUTES = listOf(10, 20, 30)

        private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, def: T): T =
            if (name == null) def else try {
                enumValueOf<T>(name)
            } catch (e: IllegalArgumentException) {
                def
            }
    }
}
