package com.local.unlocksession.data

import android.content.Context
import android.content.SharedPreferences
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState

/**
 * 会话状态与配置的持久化。
 *
 * 覆盖：会话快照（状态、会话标识、截止时间、开机归属、提醒快照）、
 * 已消费提醒标记、全局设置（监控、快捷时长、倒计时开关、提前提醒开关与时点）。
 * 截止时间使用 elapsedRealtime 毫秒值，必须与保存时的 boot 归属一起校验，
 * 严禁跨重启复用。旧版本数据迁移：旧会话无提醒快照 → 保守按空提醒处理；
 * 快捷时长与原截止时间原样保留。
 */
class SessionRepository(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------- 会话快照 ----------------

    override fun loadSnapshot(): SessionSnapshot = SessionSnapshot(
        state = enumValueOrDefault(prefs.getString(KEY_STATE, null), SessionState.NO_SESSION),
        sessionId = prefs.getLong(KEY_SESSION_ID, 0L),
        startedElapsed = prefs.getLong(KEY_STARTED_ELAPSED, 0L),
        deadlineElapsed = prefs.getLong(KEY_DEADLINE_ELAPSED, 0L),
        lockError = prefs.getString(KEY_LOCK_ERROR, null),
        alarmScheduled = prefs.getBoolean(KEY_ALARM_SCHEDULED, true),
        sessionRemindersEnabled = prefs.getBoolean(KEY_SESSION_REMINDERS_ENABLED, true),
        sessionReminderThresholds = parseLongList(prefs.getString(KEY_SESSION_REMINDER_THRESHOLDS, null))
    )

    override fun saveSnapshot(s: SessionSnapshot) {
        prefs.edit()
            .putString(KEY_STATE, s.state.name)
            .putLong(KEY_SESSION_ID, s.sessionId)
            .putLong(KEY_STARTED_ELAPSED, s.startedElapsed)
            .putLong(KEY_DEADLINE_ELAPSED, s.deadlineElapsed)
            .putString(KEY_LOCK_ERROR, s.lockError)
            .putBoolean(KEY_ALARM_SCHEDULED, s.alarmScheduled)
            .putBoolean(KEY_SESSION_REMINDERS_ENABLED, s.sessionRemindersEnabled)
            .putString(KEY_SESSION_REMINDER_THRESHOLDS, s.sessionReminderThresholds.joinToString(","))
            // 保存时刻的恢复信息：用于判断同次开机
            .putLong(KEY_SAVED_ELAPSED, android.os.SystemClock.elapsedRealtime())
            .putInt(KEY_BOOT_AT_SAVE, bootAtSave)
            .apply()
    }

    /** 保存时由控制器注入的当前 boot 计数 */
    @Volatile
    var bootAtSave: Int = -1
        private set

    override fun setBootAtSave(value: Int) {
        bootAtSave = value
    }

    /** 上次保存时的 boot 计数（读取自持久化） */
    override fun savedBootCount(): Int = prefs.getInt(KEY_BOOT_AT_SAVE, -1)

    /** 上次保存时刻的 elapsedRealtime（辅助校验单调性） */
    override fun savedElapsed(): Long = prefs.getLong(KEY_SAVED_ELAPSED, 0L)

    // ---------------- 已消费提醒标记（键："sessionId:thresholdMs"） ----------------

    override fun consumedReminders(): Set<String> =
        prefs.getStringSet(KEY_CONSUMED_REMINDERS, emptySet()) ?: emptySet()

    /**
     * 可靠保存消费标记（提示词：消费标记必须在通知请求之前保存）。
     * 返回 false 表示该标记已存在（重复投递），调用方不得再次提醒。
     */
    override fun markReminderConsumed(key: String): Boolean {
        val cur = consumedReminders()
        if (key in cur) return false
        prefs.edit().putStringSet(KEY_CONSUMED_REMINDERS, cur + key).apply()
        return true
    }

    override fun markRemindersSkipped(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val cur = consumedReminders()
        prefs.edit().putStringSet(KEY_CONSUMED_REMINDERS, cur + keys).apply()
    }

    /** 会话结束清空消费标记（只属于该会话的键） */
    override fun clearConsumedReminders(sessionId: Long) {
        val prefix = "$sessionId:"
        val keep = consumedReminders().filterNot { it.startsWith(prefix) }.toSet()
        prefs.edit().putStringSet(KEY_CONSUMED_REMINDERS, keep).apply()
    }

    // ---------------- 会话标识 ----------------

    /** 下一个可用会话标识（预览，不递增） */
    override fun peekNextSessionId(): Long = prefs.getLong(KEY_NEXT_SESSION_ID, 1L)

    /** 状态机实际使用了该标识后提交递增 */
    override fun commitSessionId(used: Long) {
        if (used >= peekNextSessionId()) {
            prefs.edit().putLong(KEY_NEXT_SESSION_ID, used + 1).apply()
        }
    }

    // ---------------- 配置 ----------------

    override fun isMonitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITORING, true)

    override fun setMonitoringEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MONITORING, value).apply()
    }

    /** 三个快捷时长（分钟），初始 10/20/30，可编辑持久保存 */
    override fun getQuickMinutes(): List<Int> {
        val raw = prefs.getString(KEY_QUICK_MINUTES, null) ?: return DEFAULT_QUICK_MINUTES.toList()
        val parsed = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parsed.size != 3 || parsed.any { it < 1 || it > 1440 }) return DEFAULT_QUICK_MINUTES.toList()
        return parsed
    }

    override fun setQuickMinutes(values: List<Int>): Boolean {
        if (values.size != 3 || values.any { it < 1 || it > 1440 }) return false
        prefs.edit().putString(KEY_QUICK_MINUTES, values.joinToString(",")).apply()
        return true
    }

    /** debug 构建专用：自定义时长按秒输入 */
    override fun isDebugSecondsMode(): Boolean = prefs.getBoolean(KEY_DEBUG_SECONDS, false)

    override fun setDebugSecondsMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_SECONDS, value).apply()
    }

    /** 通知栏倒计时开关（默认开启；只控制显示，不影响计时与提醒） */
    override fun isShowCountdown(): Boolean = prefs.getBoolean(KEY_SHOW_COUNTDOWN, true)

    override fun setShowCountdown(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_COUNTDOWN, value).apply()
    }

    /** 提前提醒总开关（默认开启） */
    override fun isRemindersEnabled(): Boolean = prefs.getBoolean(KEY_REMINDERS_ENABLED, true)

    override fun setRemindersEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, value).apply()
    }

    /** 提前提醒时点（秒，规范化：正数、去重、升序）；空列表 = 本次没有提前提醒 */
    override fun getReminderThresholds(): List<Long> {
        val raw = prefs.getString(KEY_REMINDER_THRESHOLDS, null) ?: return DEFAULT_REMINDER_THRESHOLDS.toList()
        return parseLongList(raw).filter { it > 0 }.distinct().sorted()
    }

    /** 保存时点；空列表允许（代表不提醒）；范围沿用会话上限 48h，非法输入返回 false */
    override fun setReminderThresholds(values: List<Long>): Boolean {
        val max = com.local.unlocksession.logic.DurationInput.MAX_MINUTES * 60
        if (values.any { it < 1 || it > max }) return false
        prefs.edit().putString(KEY_REMINDER_THRESHOLDS, values.distinct().sorted().joinToString(",")).apply()
        return true
    }

    companion object {
        private const val PREFS_NAME = "unlock_session"
        private const val KEY_STATE = "state"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_STARTED_ELAPSED = "started_elapsed"
        private const val KEY_DEADLINE_ELAPSED = "deadline_elapsed"
        private const val KEY_LOCK_ERROR = "lock_error"
        private const val KEY_ALARM_SCHEDULED = "alarm_scheduled"
        private const val KEY_SESSION_REMINDERS_ENABLED = "session_reminders_enabled"
        private const val KEY_SESSION_REMINDER_THRESHOLDS = "session_reminder_thresholds"
        private const val KEY_CONSUMED_REMINDERS = "consumed_reminders"
        private const val KEY_SAVED_ELAPSED = "saved_elapsed"
        private const val KEY_BOOT_AT_SAVE = "boot_at_save"
        private const val KEY_NEXT_SESSION_ID = "next_session_id"
        private const val KEY_MONITORING = "monitoring_enabled"
        private const val KEY_QUICK_MINUTES = "quick_minutes"
        private const val KEY_DEBUG_SECONDS = "debug_seconds_mode"
        private const val KEY_SHOW_COUNTDOWN = "show_countdown"
        private const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        private const val KEY_REMINDER_THRESHOLDS = "reminder_thresholds"

        val DEFAULT_QUICK_MINUTES = listOf(10, 20, 30)
        val DEFAULT_REMINDER_THRESHOLDS = listOf(60L, 30L)

        private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, def: T): T =
            if (name == null) def else try {
                enumValueOf<T>(name)
            } catch (e: IllegalArgumentException) {
                def
            }

        private fun parseLongList(raw: String?): List<Long> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
        }
    }
}
