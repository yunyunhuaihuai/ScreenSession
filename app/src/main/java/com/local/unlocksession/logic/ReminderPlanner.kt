package com.local.unlocksession.logic

/**
 * 提前提醒规划：纯 Kotlin，便于注入时钟后验证延迟任务。
 *
 * 设计要点（对应产品规则 B）：
 * - 时点以“剩余多少秒”表达；仅 0 < threshold < 会话总时长 的时点有效，
 *   等于或超过总时长的时点直接跳过（不允许会话一开始就连发提醒）；
 * - triggerElapsed = deadlineElapsed - thresholdMs；
 * - 恢复时区分“正常投递迟到”（2 秒窗口内，照常投递一次）与“历史错过”（跳过，不补发）；
 * - 该窗口只用于提醒投递判定，绝不用于提前锁屏。
 */
object ReminderPlanner {

    /** 普通投递迟到窗口：闹钟晚于计划 2 秒内仍视为准点投递；超过则按历史错过跳过 */
    const val LATE_WINDOW_MS: Long = 2000L

    /** 单个提醒计划 */
    data class PlannedReminder(
        val thresholdMs: Long,
        val triggerElapsed: Long
    )

    /** 规划结果分类 */
    enum class Outcome { DELIVER_NOW, SCHEDULE_FUTURE, SKIP_MISSED }

    /** 时点规范化：正整数、去重、升序 */
    fun normalize(thresholdsSeconds: List<Long>): List<Long> =
        thresholdsSeconds.filter { it > 0 }.distinct().sorted()

    /**
     * 按会话快照生成有效提醒计划。
     * @param thresholdsSeconds 规范化后的时点（秒）
     * @param deadlineElapsed 截止时刻
     * @param sessionDurationMs 会话总时长（deadline - started）
     */
    fun plan(
        thresholdsSeconds: List<Long>,
        deadlineElapsed: Long,
        sessionDurationMs: Long
    ): List<PlannedReminder> {
        if (sessionDurationMs <= 0) return emptyList()
        return normalize(thresholdsSeconds)
            .asSequence()
            .map { it * 1000L }
            .filter { it in 1 until sessionDurationMs }
            .map { PlannedReminder(it, deadlineElapsed - it) }
            .sortedBy { it.triggerElapsed }
            .toList()
    }

    /** 恢复/重复投递判定：到期处理优先，发送方应先保证 now < deadline 才调用本方法 */
    fun classify(planned: PlannedReminder, nowElapsed: Long): Outcome = when {
        nowElapsed < planned.triggerElapsed -> Outcome.SCHEDULE_FUTURE
        nowElapsed <= planned.triggerElapsed + LATE_WINDOW_MS -> Outcome.DELIVER_NOW
        else -> Outcome.SKIP_MISSED
    }

    /** 人类可读时点：60 秒 → “1 分钟”，90 秒 → “1 分 30 秒”，30 秒 → “30 秒” */
    fun humanize(thresholdSeconds: Long): String {
        val m = thresholdSeconds / 60
        val s = thresholdSeconds % 60
        return when {
            m > 0 && s > 0 -> "$m 分 $s 秒"
            m > 0 -> "$m 分钟"
            else -> "$s 秒"
        }
    }
}
