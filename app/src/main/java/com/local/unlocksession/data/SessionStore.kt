package com.local.unlocksession.data

import com.local.unlocksession.logic.SessionSnapshot

/**
 * 会话存储抽象：生产实现 SharedPreferences（[SessionRepository]），
 * 测试注入内存假实现。
 */
interface SessionStore {
    fun loadSnapshot(): SessionSnapshot
    fun saveSnapshot(s: SessionSnapshot)

    fun setBootAtSave(value: Int)
    fun savedBootCount(): Int
    fun savedElapsed(): Long

    fun peekNextSessionId(): Long
    fun commitSessionId(used: Long)

    fun consumedReminders(): Set<String>
    /** 返回 false = 标记已存在（重复投递） */
    fun markReminderConsumed(key: String): Boolean
    fun markRemindersSkipped(keys: Collection<String>)
    fun clearConsumedReminders(sessionId: Long)

    fun isMonitoringEnabled(): Boolean
    fun setMonitoringEnabled(value: Boolean)

    fun getQuickMinutes(): List<Int>
    fun setQuickMinutes(values: List<Int>): Boolean

    fun isDebugSecondsMode(): Boolean
    fun setDebugSecondsMode(value: Boolean)

    fun isShowCountdown(): Boolean
    fun setShowCountdown(value: Boolean)

    fun isRemindersEnabled(): Boolean
    fun setRemindersEnabled(value: Boolean)

    fun getReminderThresholds(): List<Long>
    fun setReminderThresholds(values: List<Long>): Boolean
}
