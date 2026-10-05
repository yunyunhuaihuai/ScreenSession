package com.local.unlocksession.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话状态机单元测试：关键状态转换、重复事件、旧会话到期、恢复处理、输入校验、
 * R3 精确到期边界、R4 同解锁周期重复、R5 通话熄屏区分、R1 旧会话回传失效。
 * 纯 JVM 测试，不覆盖真机后台行为与真实锁屏（那部分必须真机验收）。
 */
class SessionStateMachineTest {

    private val noSession = SessionSnapshot()

    private fun reduce(cur: SessionSnapshot, ev: SessionEvent) = SessionMachine.reduce(cur, ev)

    private fun screenOff(
        kgLocked: Boolean = true,
        devLocked: Boolean = kgLocked,
        inCall: Boolean = false,
        secure: Boolean = false
    ) = SessionEvent.ScreenOff(kgLocked, devLocked, secure, inCall)

    // ------------------------------------------------------------------
    // 解锁 → 待选择
    // ------------------------------------------------------------------

    @Test
    fun `解锁创建待选择会话`() {
        val t = reduce(noSession, SessionEvent.UserPresent(keyguardLocked = false, nextSessionId = 7, nowElapsed = 1000))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(7L, t.snapshot.sessionId)
        assertTrue(SessionAction.ShowSelectionOverlay in t.actions)
    }

    @Test
    fun `keyguard仍锁定时USER_PRESENT是过期广播`() {
        val t = reduce(noSession, SessionEvent.UserPresent(true, 7, 1000))
        assertEquals(noSession, t.snapshot)
    }

    @Test
    fun `待选择中重复解锁广播保持幂等且不换会话标识`() {
        val pending = reduce(noSession, SessionEvent.UserPresent(false, 7, 1000)).snapshot
        val t = reduce(pending, SessionEvent.UserPresent(false, 8, 2000))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(7L, t.snapshot.sessionId)
        assertTrue(SessionAction.Persist !in t.actions)
    }

    @Test
    fun `只亮屏不创建会话`() {
        val t = reduce(noSession, SessionEvent.ScreenOn(interactive = false, keyguardLocked = true, nextSessionId = 7))
        assertEquals(noSession, t.snapshot)
    }

    @Test
    fun `亮屏且未锁且无会话触发漏事件安全网`() {
        val t = reduce(noSession, SessionEvent.ScreenOn(interactive = true, keyguardLocked = false, nextSessionId = 9))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(9L, t.snapshot.sessionId)
    }

    @Test
    fun `活动中亮屏且keyguard已锁补记漏掉的熄屏结束`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.ScreenOn(interactive = true, keyguardLocked = true, nextSessionId = 9))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertTrue(SessionAction.CancelAlarm in t.actions)
    }

    // ------------------------------------------------------------------
    // 选择 → 计时 / 不限
    // ------------------------------------------------------------------

    private fun pending(id: Long = 7): SessionSnapshot =
        reduce(noSession, SessionEvent.UserPresent(false, id, 1000)).snapshot

    @Test
    fun `选择时长从确认时刻起算截止时间并设定闹钟`() {
        val now = 100_000L
        val t = reduce(pending(), SessionEvent.Selected(7, false, 10 * 60_000L, now))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertEquals(now + 10 * 60_000L, t.snapshot.deadlineElapsed)
        assertTrue(SessionAction.ScheduleAlarm(7, now + 10 * 60_000L) in t.actions)
        assertTrue(SessionAction.HideSelectionOverlay in t.actions)
    }

    @Test
    fun `选择不限不创建到期任务`() {
        val now = 100_000L
        val t = reduce(pending(), SessionEvent.Selected(7, true, 0, now))
        assertEquals(SessionState.UNLIMITED, t.snapshot.state)
        assertEquals(0L, t.snapshot.deadlineElapsed)
        assertTrue(t.actions.filterIsInstance<SessionAction.ScheduleAlarm>().isEmpty())
        assertTrue(SessionAction.HideSelectionOverlay in t.actions)
    }

    @Test
    fun `选择时长非法则保持待选择`() {
        val p = pending()
        for (bad in listOf(0L, -5L, SessionMachine.MAX_SESSION_MS + 1)) {
            val t = reduce(p, SessionEvent.Selected(7, false, bad, 1000))
            assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        }
    }

    @Test
    fun `选择对应旧会话标识则忽略`() {
        val p = pending(7)
        val t = reduce(p, SessionEvent.Selected(3, false, 10 * 60_000L, 1000))
        assertEquals(p, t.snapshot)
    }

    // ------------------------------------------------------------------
    // 到期闹钟：标识核对、精确到期边界（R3）、幂等
    // ------------------------------------------------------------------

    private fun timing(id: Long = 7, now: Long = 100_000L, duration: Long = 60_000L): SessionSnapshot =
        reduce(pending(id), SessionEvent.Selected(id, false, duration, now)).snapshot

    @Test
    fun `新会话中旧会话到期事件被忽略`() {
        val cur = timing(id = 7)
        val t = reduce(cur, SessionEvent.AlarmFired(firedSessionId = 3, nowElapsed = cur.deadlineElapsed + 1))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertTrue(t.actions.isEmpty())
    }

    @Test
    fun `到期触发锁屏请求`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.AlarmFired(7, cur.deadlineElapsed + 10))
        assertEquals(SessionState.LOCK_REQUESTED, t.snapshot.state)
        assertTrue(SessionAction.RequestLockNow in t.actions)
        assertTrue(SessionAction.ScheduleLockWatchdog(SessionMachine.LOCK_WATCHDOG_MS) in t.actions)
    }

    @Test
    fun `重复到期事件不再重复锁屏`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        assertEquals(SessionState.LOCK_REQUESTED, requested.state)
        val again = reduce(requested, SessionEvent.AlarmFired(7, 160_002))
        assertEquals(requested, again.snapshot)
        assertTrue(again.actions.isEmpty())
    }

    @Test
    fun `R3_截止前1毫秒的闹钟重排而不锁屏`() {
        val cur = timing(duration = 60_000L, now = 100_000L) // deadline=160_000
        val t = reduce(cur, SessionEvent.AlarmFired(7, 159_999))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertEquals(listOf(SessionAction.ScheduleAlarm(7, 160_000L)), t.actions)
    }

    @Test
    fun `R3_恰好截止与晚1毫秒都执行锁屏`() {
        val cur = timing(duration = 60_000L, now = 100_000L) // deadline=160_000
        assertEquals(
            SessionState.LOCK_REQUESTED,
            reduce(cur, SessionEvent.AlarmFired(7, 160_000)).snapshot.state
        )
        assertEquals(
            SessionState.LOCK_REQUESTED,
            reduce(cur, SessionEvent.AlarmFired(7, 160_001)).snapshot.state
        )
    }

    @Test
    fun `R3_容差范围内不再提前锁屏`() {
        // 旧实现 deadline-1500 即触发锁屏；新规则必须到点才锁
        val cur = timing(duration = 60_000L, now = 100_000L)
        assertEquals(
            SessionState.TIMING,
            reduce(cur, SessionEvent.AlarmFired(7, cur.deadlineElapsed - 1500)).snapshot.state
        )
        assertEquals(
            SessionState.TIMING,
            reduce(cur, SessionEvent.AlarmFired(7, cur.deadlineElapsed - 1501)).snapshot.state
        )
    }

    @Test
    fun `闹钟排定失败如实标记且成功后恢复`() {
        val cur = timing()
        val failed = reduce(cur, SessionEvent.AlarmScheduleOutcome(7, false)).snapshot
        assertTrue(!failed.alarmScheduled)
        val ok = reduce(failed, SessionEvent.AlarmScheduleOutcome(7, true)).snapshot
        assertTrue(ok.alarmScheduled)
    }

    @Test
    fun `闹钟排定结果与当前会话不符则忽略`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.AlarmScheduleOutcome(3, false))
        assertEquals(cur, t.snapshot)
    }

    // ------------------------------------------------------------------
    // 熄屏结束会话（R5：区分通话接近黑屏与真实锁屏）
    // ------------------------------------------------------------------

    @Test
    fun `计时中熄屏结束会话并取消闹钟`() {
        val cur = timing()
        val t = reduce(cur, screenOff())
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertEquals(0L, t.snapshot.sessionId)
        assertTrue(SessionAction.CancelAlarm in t.actions)
        assertTrue(SessionAction.RequestLockOnScreenOff in t.actions)
    }

    @Test
    fun `R5_通话接近黑屏不结束会话`() {
        val cur = timing()
        val t = reduce(cur, screenOff(kgLocked = false, devLocked = false, inCall = true))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertTrue(t.actions.isEmpty())
    }

    @Test
    fun `R5_通话中真实锁屏仍结束会话`() {
        val cur = timing()
        val t = reduce(cur, screenOff(kgLocked = true, devLocked = true, inCall = true))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
    }

    @Test
    fun `R5_到期锁屏确认不受通话影响`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, screenOff(kgLocked = false, devLocked = false, inCall = true))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertEquals("锁屏请求后确认熄屏：到期锁屏完成", t.note)
    }

    @Test
    fun `本次不限熄屏后结束且下次解锁重新选择`() {
        val unlim = reduce(pending(), SessionEvent.Selected(7, true, 0, 1000)).snapshot
        val off = reduce(unlim, screenOff())
        assertEquals(SessionState.NO_SESSION, off.snapshot.state)
        val again = reduce(off.snapshot, SessionEvent.UserPresent(false, 9, 2000))
        assertEquals(SessionState.PENDING_SELECTION, again.snapshot.state)
        assertEquals(9L, again.snapshot.sessionId)
    }

    @Test
    fun `待选择中熄屏放弃选择且无遗留闹钟`() {
        val t = reduce(pending(), screenOff())
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertTrue(SessionAction.HideSelectionOverlay in t.actions)
        assertTrue(SessionAction.CancelAlarm !in t.actions)
    }

    // ------------------------------------------------------------------
    // R4：同解锁周期重复 USER_PRESENT
    // ------------------------------------------------------------------

    @Test
    fun `R4_会话开始后短窗口内的重复USER_PRESENT不重置会话`() {
        val cur = timing(now = 100_000L) // started=100_000
        val t = reduce(cur, SessionEvent.UserPresent(false, 11, nowElapsed = 102_500))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertEquals(7L, t.snapshot.sessionId)
        assertTrue(SessionAction.CancelAlarm !in t.actions)
    }

    @Test
    fun `R4_距会话开始较远的USER_PRESENT判定漏屏自愈重新选择`() {
        val cur = timing(now = 100_000L, duration = 10 * 60_000L)
        val t = reduce(cur, SessionEvent.UserPresent(false, 11, nowElapsed = 100_000 + 60_000))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(11L, t.snapshot.sessionId)
        assertTrue(SessionAction.CancelAlarm in t.actions)
    }

    // ------------------------------------------------------------------
    // R1：旧会话的 LockOutcome / LockWatchdog 失效
    // ------------------------------------------------------------------

    @Test
    fun `R1_旧会话的锁屏结果不消费`() {
        val cur = timing(id = 9, now = 200_000L)
        val t = reduce(cur, SessionEvent.LockOutcome(3, false, "旧会话错误", 201_000))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertNull(t.snapshot.lockError)
    }

    @Test
    fun `R1_旧会话的看门狗不消费`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.LockWatchdog(3, 160_500))
        assertEquals(SessionState.LOCK_REQUESTED, t.snapshot.state)
    }

    @Test
    fun `R1_当前会话的看门狗超时判失败`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.LockWatchdog(7, 170_001))
        assertEquals(SessionState.LOCK_FAILED, t.snapshot.state)
        assertTrue(t.snapshot.lockError!!.contains("未确认熄屏"))
    }

    @Test
    fun `lockNow失败进入锁屏失败状态并记录原因`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.LockOutcome(7, false, "设备管理员未启用", 160_100))
        assertEquals(SessionState.LOCK_FAILED, t.snapshot.state)
        assertEquals("设备管理员未启用", t.snapshot.lockError)
    }

    @Test
    fun `锁屏重试成功回到锁屏请求状态`() {
        val failed = reduce(
            reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot,
            SessionEvent.LockOutcome(7, false, "x", 160_100)
        ).snapshot
        val t = reduce(failed, SessionEvent.LockOutcome(7, true, null, 163_200))
        assertEquals(SessionState.LOCK_REQUESTED, t.snapshot.state)
        assertNull(t.snapshot.lockError)
    }

    @Test
    fun `锁屏请求后确认熄屏才会话结束`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, screenOff())
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
    }

    @Test
    fun `锁屏请求未生效即被解锁则重新选择`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.UserPresent(false, 12, 160_500))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(12L, t.snapshot.sessionId)
    }

    // ------------------------------------------------------------------
    // 恢复（含 R3 精确边界与 R6 重启补面板）
    // ------------------------------------------------------------------

    @Test
    fun `恢复_重启后旧会话作废不跨重启复用截止时间`() {
        val cur = timing(now = 100_000, duration = 60_000) // deadline=160_000（旧开机）
        val t = reduce(cur, SessionEvent.Recover(true, false, sameBoot = false, canCreateSession = true, nowElapsed = 5_000, nextSessionId = 20))
        // R6：重启作废旧会话；此刻已解锁则直接补待选择，但绝不复用旧截止时间
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(20L, t.snapshot.sessionId)
        assertEquals(0L, t.snapshot.deadlineElapsed)
        assertTrue(SessionAction.CancelAlarm in t.actions)
    }

    @Test
    fun `恢复_重启后仍锁屏则不弹面板`() {
        val cur = timing(now = 100_000, duration = 60_000)
        val t = reduce(cur, SessionEvent.Recover(false, true, false, true, 5_000, 20))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertTrue(SessionAction.ShowSelectionOverlay !in t.actions)
    }

    @Test
    fun `R6_新开机且已解锁直接补待选择`() {
        val cur = timing(now = 100_000, duration = 60_000)
        val t = reduce(cur, SessionEvent.Recover(true, false, false, true, 5_000, 20))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(20L, t.snapshot.sessionId)
        assertTrue(SessionAction.ShowSelectionOverlay in t.actions)
        assertTrue(SessionAction.CancelAlarm in t.actions)
    }

    @Test
    fun `R6_新开机已解锁但能力未就绪不建会话`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.Recover(true, false, false, false, 5_000, 20))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
    }

    @Test
    fun `R6_新开机仍锁屏不弹面板`() {
        val t = reduce(noSession, SessionEvent.Recover(false, true, false, true, 5_000, 20))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertTrue(SessionAction.ShowSelectionOverlay !in t.actions)
    }

    @Test
    fun `恢复_同次开机且已解锁的计时会话沿用原截止时间`() {
        val cur = timing(now = 100_000, duration = 10 * 60_000L) // deadline=700_000
        val t = reduce(cur, SessionEvent.Recover(true, false, true, true, nowElapsed = 250_000, nextSessionId = 20))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertEquals(700_000L, t.snapshot.deadlineElapsed)
        assertEquals(listOf(SessionAction.ScheduleAlarm(7, 700_000L)), t.actions.filterIsInstance<SessionAction.ScheduleAlarm>())
    }

    @Test
    fun `R3_恢复时距截止还有1毫秒不提前锁屏`() {
        val cur = timing(now = 100_000, duration = 10 * 60_000L) // deadline=700_000
        val t = reduce(cur, SessionEvent.Recover(true, false, true, true, nowElapsed = 699_999, nextSessionId = 20))
        assertEquals(SessionState.TIMING, t.snapshot.state)
    }

    @Test
    fun `R3_恢复时恰好到期执行到期锁屏`() {
        val cur = timing(now = 100_000, duration = 10 * 60_000L)
        val t = reduce(cur, SessionEvent.Recover(true, false, true, true, nowElapsed = 700_000, nextSessionId = 20))
        assertEquals(SessionState.LOCK_REQUESTED, t.snapshot.state)
        assertTrue(SessionAction.RequestLockNow in t.actions)
    }

    @Test
    fun `恢复_同次开机但当前已锁屏则会话结束`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.Recover(false, true, true, true, 150_000, 20))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
    }

    @Test
    fun `恢复_同次开机已解锁但无会话则补上待选择`() {
        val t = reduce(noSession, SessionEvent.Recover(true, false, true, true, 1000, 20))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(20L, t.snapshot.sessionId)
        assertTrue(SessionAction.ShowSelectionOverlay in t.actions)
    }

    @Test
    fun `恢复_能力未就绪不创建待选择`() {
        val t = reduce(noSession, SessionEvent.Recover(true, false, true, false, 1000, 20))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
    }

    @Test
    fun `恢复_待选择中重新显示选择层且标识不变`() {
        val p = pending(7)
        val t = reduce(p, SessionEvent.Recover(true, false, true, true, 1000, 20))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(7L, t.snapshot.sessionId)
        assertTrue(SessionAction.ShowSelectionOverlay in t.actions)
    }

    @Test
    fun `恢复_本次不限在未锁屏时继续有效`() {
        val unlim = reduce(pending(), SessionEvent.Selected(7, true, 0, 1000)).snapshot
        val t = reduce(unlim, SessionEvent.Recover(true, false, true, true, 2000, 20))
        assertEquals(SessionState.UNLIMITED, t.snapshot.state)
    }

    // ------------------------------------------------------------------
    // 输入校验
    // ------------------------------------------------------------------

    @Test
    fun `分钟输入拒绝空值零负数非数字和超范围`() {
        assertNull(DurationInput.parseMinutesToMs(null))
        assertNull(DurationInput.parseMinutesToMs(""))
        assertNull(DurationInput.parseMinutesToMs("  "))
        assertNull(DurationInput.parseMinutesToMs("0"))
        assertNull(DurationInput.parseMinutesToMs("-3"))
        assertNull(DurationInput.parseMinutesToMs("abc"))
        assertNull(DurationInput.parseMinutesToMs("1.5"))
        assertNull(DurationInput.parseMinutesToMs("12a"))
        assertNull(DurationInput.parseMinutesToMs("2881"))
        assertNull(DurationInput.parseMinutesToMs("99999999999999999999"))
    }

    @Test
    fun `分钟输入接受合法值并换算毫秒`() {
        assertEquals(60_000L, DurationInput.parseMinutesToMs("1"))
        assertEquals(1200_000L, DurationInput.parseMinutesToMs(" 20 "))
        assertEquals(2880 * 60_000L, DurationInput.parseMinutesToMs("2880"))
    }

    @Test
    fun `秒输入仅用于debug且范围受限`() {
        assertEquals(1000L, DurationInput.parseSecondsToMs("1"))
        assertNull(DurationInput.parseSecondsToMs("0"))
        assertNull(DurationInput.parseSecondsToMs("7201"))
        assertEquals(7200_000L, DurationInput.parseSecondsToMs("7200"))
    }

    @Test
    fun `提醒时点列表拒绝空值重复和超范围`() {
        assertNull(ReminderInput.parseList(null))
        assertNull(ReminderInput.parseList(""))
        assertNull(ReminderInput.parseList("60,60"))
        assertNull(ReminderInput.parseList("0"))
        assertNull(ReminderInput.parseList("-3"))
        assertNull(ReminderInput.parseList("172801")) // 上限 = 会话上限 48h = 172800 秒
        assertNull(ReminderInput.parseList("abc"))
    }

    @Test
    fun `提醒时点列表接受范围内合法值`() {
        assertEquals(listOf(86401L), ReminderInput.parseList("86401"))
        assertEquals(listOf(172800L), ReminderInput.parseList("172800"))
    }

    @Test
    fun `提醒时点列表规范化排序`() {
        assertEquals(listOf(30L, 60L), ReminderInput.parseList("60,30"))
        assertEquals(listOf(45L), ReminderInput.parseList(" 45 "))
    }

    @Test
    fun `溢出保护使截止时间饱和`() {
        val t = reduce(pending(), SessionEvent.Selected(7, false, SessionMachine.MAX_SESSION_MS, Long.MAX_VALUE / 2))
        assertEquals(Long.MAX_VALUE / 2 + SessionMachine.MAX_SESSION_MS, t.snapshot.deadlineElapsed)
    }
}
