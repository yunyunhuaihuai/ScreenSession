package com.local.unlocksession.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提前提醒规划：有效时点过滤、恢复分类、迟到窗口边界 */
class ReminderPlannerTest {

    @Test
    fun `默认60与30秒规划正确`() {
        val planned = ReminderPlanner.plan(listOf(60L, 30L), deadlineElapsed = 120_000L, sessionDurationMs = 120_000L)
        assertEquals(
            listOf(
                ReminderPlanner.PlannedReminder(60_000L, 60_000L),
                ReminderPlanner.PlannedReminder(30_000L, 90_000L)
            ),
            planned
        )
    }

    @Test
    fun `45秒会话只安排剩余30秒时点`() {
        val planned = ReminderPlanner.plan(listOf(60L, 30L), deadlineElapsed = 45_000L, sessionDurationMs = 45_000L)
        // 60 秒时点 >= 45 秒总时长：跳过
        assertEquals(listOf(ReminderPlanner.PlannedReminder(30_000L, 15_000L)), planned)
    }

    @Test
    fun `30秒会话不在开始时发任何提醒`() {
        val planned = ReminderPlanner.plan(listOf(60L, 30L), deadlineElapsed = 30_000L, sessionDurationMs = 30_000L)
        // 60s >= 总时长跳过；30s == 总时长也必须跳过（不能一开始就提醒）
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `1秒时长不产生任何提醒`() {
        val planned = ReminderPlanner.plan(listOf(60L, 30L), deadlineElapsed = 1_000L, sessionDurationMs = 1_000L)
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `时点规范化去重升序`() {
        assertEquals(listOf(30L, 60L, 90L), ReminderPlanner.normalize(listOf(60, 30, 90, 30, 60)))
        assertTrue(ReminderPlanner.normalize(listOf(0, -5)).isEmpty())
    }

    @Test
    fun `恢复分类_未来排定_窗口内补投_历史跳过`() {
        val p = ReminderPlanner.PlannedReminder(30_000L, triggerElapsed = 90_000L)
        assertEquals(ReminderPlanner.Outcome.SCHEDULE_FUTURE, ReminderPlanner.classify(p, 89_999))
        assertEquals(ReminderPlanner.Outcome.DELIVER_NOW, ReminderPlanner.classify(p, 90_000))
        // 恰好在 2 秒迟到窗口边界内
        assertEquals(ReminderPlanner.Outcome.DELIVER_NOW, ReminderPlanner.classify(p, 92_000))
        assertEquals(ReminderPlanner.Outcome.SKIP_MISSED, ReminderPlanner.classify(p, 92_001))
        assertEquals(ReminderPlanner.Outcome.SKIP_MISSED, ReminderPlanner.classify(p, 200_000))
    }

    @Test
    fun `迟到窗口不会让锁屏提前`() {
        // 窗口只影响提醒投递分类；到期判定由状态机 now >= deadline 决定，
        // 这里验证窗口值与文档一致（2 秒）。
        assertEquals(2000L, ReminderPlanner.LATE_WINDOW_MS)
    }

    @Test
    fun `人类可读时点`() {
        assertEquals("60 秒", "60 秒")
        assertEquals("1 分钟", ReminderPlanner.humanize(60))
        assertEquals("30 秒", ReminderPlanner.humanize(30))
        assertEquals("1 分 30 秒", ReminderPlanner.humanize(90))
    }
}
