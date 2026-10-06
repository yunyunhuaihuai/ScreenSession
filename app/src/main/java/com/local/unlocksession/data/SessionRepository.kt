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

    /**
     * 关键快照同步落盘：commit() 在调用线程（控制器单线程，非主线程）上确认磁盘写入。
     * 返回 false = 写盘失败：内存值已更新，但进程被回收后可能回退到旧快照，
     * 调用方必须如实记录，不能当作已持久化。
     */
    override fun saveSnapshot(s: SessionSnapshot): Boolean {
        val ok = try {
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
                .commit()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "saveSnapshot 失败", e)
            false
        }
        return ok
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
     * 可靠保存消费标记（消费标记必须在通知请求之前确认落盘）。
     * 注意：通知请求与磁盘事务无法组成原子操作——先标记后投递的崩溃窗口是
     * "标记已存、通知未发"（宁可漏提醒不可重发）；本产品优先避免重复历史提醒。
     */
    override fun markReminderConsumed(key: String): ReminderMark {
        val cur = consumedReminders()
        if (key in cur) return ReminderMark.DUPLICATE
        val ok = try {
            prefs.edit().putStringSet(KEY_CONSUMED_REMINDERS, cur + key).commit()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "markReminderConsumed 失败 key=$key", e)
            false
        }
        return if (ok) ReminderMark.NEW else ReminderMark.WRITE_FAILED
    }

    override fun markRemindersSkipped(keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return true
        val cur = consumedReminders()
        return try {
            prefs.edit().putStringSet(KEY_CONSUMED_REMINDERS, cur + keys).commit()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "markRemindersSkipped 失败", e)
            false
        }
    }

    /** 会话结束清空消费标记（只属于该会话的键） */
    override fun clearConsumedReminders(sessionId: Long): Boolean {
        val prefix = "$sessionId:"
        val keep = consumedReminders().filterNot { it.startsWith(prefix) }.toSet()
        return try {
            prefs.edit().putStringSet(KEY_CONSUMED_REMINDERS, keep).commit()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clearConsumedReminders 失败 sessionId=$sessionId", e)
            false
        }
    }

    // ---------------- 会话标识 ----------------

    /** 下一个可用会话标识（预览，不递增） */
    override fun peekNextSessionId(): Long = prefs.getLong(KEY_NEXT_SESSION_ID, 1L)

    /**
     * 状态机实际使用了该标识后提交递增。同步落盘且先于快照保存：
     * 即使快照写盘失败，旧标识也不会在恢复后被复用（最多跳号，不重号）。
     */
    override fun commitSessionId(used: Long): Boolean {
        if (used < peekNextSessionId()) return true
        return try {
            prefs.edit().putLong(KEY_NEXT_SESSION_ID, used + 1).commit()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "commitSessionId 失败 used=$used", e)
            false
        }
    }

    // ---------------- 配置 ----------------
    // 以下为普通配置项：允许 apply() 异步落盘（主线程可调用，不做关键同步写入）。
    // 它们不参与会话身份与截止时间的恢复正确性，丢失一次只影响下次偏好。

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
        private const val TAG = "SessionRepository"
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
