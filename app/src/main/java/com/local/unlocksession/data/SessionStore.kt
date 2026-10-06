package com.local.unlocksession.data

import com.local.unlocksession.logic.SessionSnapshot

/**
 * 关键会话状态写入的结果。
 *
 * 持久化边界约定：这些接口由控制器单线程（非主线程）调用，实现方必须同步落盘
 * （SharedPreferences.commit() 或等价事务）并返回真实结果：
 * - 调用方据此区分"标记已经存在（重复投递）"与"写入失败（结果未知）"；
 * - 写入失败时宁可保守跳过投递，也不伪装成去重成功。
 */
enum class ReminderMark {
    /** 本次新写入并已确认落盘 */
    NEW,

    /** 标记已存在：重复投递被抑制 */
    DUPLICATE,

    /** 磁盘写入失败：状态未知，调用方必须按"未能可靠持久化"处理 */
    WRITE_FAILED
}

/**
 * 会话存储抽象：生产实现 SharedPreferences（[SessionRepository]），
 * 测试注入内存假实现。
 *
 * 关键会话状态（快照、会话身份、开机归属、截止时间、提醒消费标记）的写入
 * 使用同步落盘并返回结果；普通配置项（快捷时长、显示开关等）允许异步。
 */
interface SessionStore {
    fun loadSnapshot(): SessionSnapshot

    /** 同步落盘；返回 false = 磁盘写入失败（内存值已更新，但重启后可能丢失） */
    fun saveSnapshot(s: SessionSnapshot): Boolean

    fun setBootAtSave(value: Int)
    fun savedBootCount(): Int
    fun savedElapsed(): Long

    fun peekNextSessionId(): Long

    /** 同步落盘；返回 false = 磁盘写入失败 */
    fun commitSessionId(used: Long): Boolean

    fun consumedReminders(): Set<String>

    /** 消费标记写入：区分新写入 / 已存在 / 写入失败（见 [ReminderMark]） */
    fun markReminderConsumed(key: String): ReminderMark

    /** 跳过标记批量写入；返回 false = 磁盘写入失败 */
    fun markRemindersSkipped(keys: Collection<String>): Boolean

    /** 会话结束清理该会话的消费标记；返回 false = 磁盘写入失败 */
    fun clearConsumedReminders(sessionId: Long): Boolean

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
