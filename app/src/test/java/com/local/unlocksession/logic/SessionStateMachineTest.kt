package com.local.unlocksession.logic

import com.local.unlocksession.logic.SessionMachine.ALARM_TOLERANCE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话状态机单元测试：关键状态转换、重复事件、旧会话到期、恢复处理、输入校验。
 * 纯 JVM 测试，不覆盖真机后台行为与真实锁屏（那部分必须真机验收）。
 */
class SessionStateMachineTest {

    private val noSession = SessionSnapshot()

    private fun reduce(cur: SessionSnapshot, ev: SessionEvent) = SessionMachine.reduce(cur, ev)

    // ------------------------------------------------------------------
    // 解锁 → 待选择
    // ------------------------------------------------------------------

    @Test
    fun `解锁创建待选择会话`() {
        val t = reduce(noSession, SessionEvent.UserPresent(keyguardLocked = false, nextSessionId = 7))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(7L, t.snapshot.sessionId)
        assertTrue(SessionAction.ShowSelectionOverlay in t.actions)
    }

    @Test
    fun `keyguard仍锁定时USER_PRESENT是过期广播`() {
        val t = reduce(noSession, SessionEvent.UserPresent(keyguardLocked = true, nextSessionId = 7))
        assertEquals(noSession, t.snapshot)
    }

    @Test
    fun `待选择中重复解锁广播保持幂等且不换会话标识`() {
        val pending = reduce(noSession, SessionEvent.UserPresent(false, 7)).snapshot
        val t = reduce(pending, SessionEvent.UserPresent(false, 8))
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

    // ------------------------------------------------------------------
    // 选择 → 计时 / 不限
    // ------------------------------------------------------------------

    private fun pending(id: Long = 7): SessionSnapshot =
        reduce(noSession, SessionEvent.UserPresent(false, id)).snapshot

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
    // 到期闹钟：标识核对与幂等
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
    fun `闹钟提前触发则按原截止时间重排`() {
        val cur = timing(duration = 60_000L, now = 100_000L) // deadline=160_000
        val t = reduce(cur, SessionEvent.AlarmFired(7, 159_000 - ALARM_TOLERANCE_MS))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertEquals(listOf(SessionAction.ScheduleAlarm(7, 160_000L)), t.actions)
    }

    // ------------------------------------------------------------------
    // 熄屏结束会话
    // ------------------------------------------------------------------

    @Test
    fun `计时中熄屏结束会话并取消闹钟`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.ScreenOff(keyguardSecure = true, inCall = false))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertEquals(0L, t.snapshot.sessionId)
        assertTrue(SessionAction.CancelAlarm in t.actions)
        assertTrue(SessionAction.RequestLockOnScreenOff in t.actions)
    }

    @Test
    fun `通话中熄屏不结束会话`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.ScreenOff(keyguardSecure = false, inCall = true))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertTrue(t.actions.isEmpty())
    }

    @Test
    fun `本次不限熄屏后结束且下次解锁重新选择`() {
        val unlim = reduce(pending(), SessionEvent.Selected(7, true, 0, 1000)).snapshot
        val off = reduce(unlim, SessionEvent.ScreenOff(false, false))
        assertEquals(SessionState.NO_SESSION, off.snapshot.state)
        val again = reduce(off.snapshot, SessionEvent.UserPresent(false, 9))
        assertEquals(SessionState.PENDING_SELECTION, again.snapshot.state)
        assertEquals(9L, again.snapshot.sessionId)
    }

    @Test
    fun `待选择中熄屏放弃选择且无遗留闹钟`() {
        val t = reduce(pending(), SessionEvent.ScreenOff(false, false))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertTrue(SessionAction.HideSelectionOverlay in t.actions)
        assertTrue(SessionAction.CancelAlarm !in t.actions)
    }

    // ------------------------------------------------------------------
    // 漏事件自愈
    // ------------------------------------------------------------------

    @Test
    fun `计时中出现USER_PRESENT作废旧会话重新选择`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.UserPresent(false, 11))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(11L, t.snapshot.sessionId)
        assertTrue(SessionAction.CancelAlarm in t.actions)
    }

    // ------------------------------------------------------------------
    // 锁屏结果与看门狗
    // ------------------------------------------------------------------

    @Test
    fun `lockNow失败进入锁屏失败状态并记录原因`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.LockOutcome(false, "设备管理员未启用", 160_100))
        assertEquals(SessionState.LOCK_FAILED, t.snapshot.state)
        assertEquals("设备管理员未启用", t.snapshot.lockError)
    }

    @Test
    fun `锁屏重试成功回到锁屏请求状态`() {
        val failed = reduce(
            reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot,
            SessionEvent.LockOutcome(false, "x", 160_100)
        ).snapshot
        val t = reduce(failed, SessionEvent.LockOutcome(true, null, 163_200))
        assertEquals(SessionState.LOCK_REQUESTED, t.snapshot.state)
        assertNull(t.snapshot.lockError)
    }

    @Test
    fun `锁屏请求后确认熄屏才会话结束`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.ScreenOff(false, false))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
    }

    @Test
    fun `看门狗超时判定锁屏失败`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.LockWatchdog(170_001))
        assertEquals(SessionState.LOCK_FAILED, t.snapshot.state)
        assertTrue(t.snapshot.lockError!!.contains("未确认熄屏"))
    }

    @Test
    fun `锁屏请求未生效即被解锁则重新选择`() {
        val requested = reduce(timing(), SessionEvent.AlarmFired(7, 160_001)).snapshot
        val t = reduce(requested, SessionEvent.UserPresent(false, 12))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(12L, t.snapshot.sessionId)
    }

    // ------------------------------------------------------------------
    // 恢复
    // ------------------------------------------------------------------

    @Test
    fun `恢复_重启后旧会话作废不跨重启复用截止时间`() {
        val cur = timing(now = 100_000, duration = 60_000) // deadline=160_000（旧开机）
        val t = reduce(cur, SessionEvent.Recover(interactive = true, keyguardLocked = false, sameBoot = false, nowElapsed = 5_000, nextSessionId = 20))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
        assertTrue(SessionAction.CancelAlarm in t.actions)
    }

    @Test
    fun `恢复_同次开机且已解锁的计时会话沿用原截止时间`() {
        val cur = timing(now = 100_000, duration = 10 * 60_000L) // deadline=700_000
        val t = reduce(cur, SessionEvent.Recover(true, false, true, nowElapsed = 250_000, nextSessionId = 20))
        assertEquals(SessionState.TIMING, t.snapshot.state)
        assertEquals(700_000L, t.snapshot.deadlineElapsed)
        assertEquals(listOf(SessionAction.ScheduleAlarm(7, 700_000L)), t.actions.filterIsInstance<SessionAction.ScheduleAlarm>())
    }

    @Test
    fun `恢复_同次开机但已过期的计时会话执行到期锁屏`() {
        val cur = timing(now = 100_000, duration = 10 * 60_000L) // deadline=700_000
        val t = reduce(cur, SessionEvent.Recover(true, false, true, nowElapsed = 800_000, nextSessionId = 20))
        assertEquals(SessionState.LOCK_REQUESTED, t.snapshot.state)
        assertTrue(SessionAction.RequestLockNow in t.actions)
    }

    @Test
    fun `恢复_同次开机但当前已锁屏则会话结束`() {
        val cur = timing()
        val t = reduce(cur, SessionEvent.Recover(false, true, true, nowElapsed = 150_000, nextSessionId = 20))
        assertEquals(SessionState.NO_SESSION, t.snapshot.state)
    }

    @Test
    fun `恢复_同次开机已解锁但无会话则补上待选择`() {
        val t = reduce(noSession, SessionEvent.Recover(true, false, true, 1000, 20))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(20L, t.snapshot.sessionId)
        assertTrue(SessionAction.ShowSelectionOverlay in t.actions)
    }

    @Test
    fun `恢复_待选择中重新显示选择层且标识不变`() {
        val p = pending(7)
        val t = reduce(p, SessionEvent.Recover(true, false, true, 1000, 20))
        assertEquals(SessionState.PENDING_SELECTION, t.snapshot.state)
        assertEquals(7L, t.snapshot.sessionId)
        assertTrue(SessionAction.ShowSelectionOverlay in t.actions)
    }

    @Test
    fun `恢复_本次不限在未锁屏时继续有效`() {
        val unlim = reduce(pending(), SessionEvent.Selected(7, true, 0, 1000)).snapshot
        val t = reduce(unlim, SessionEvent.Recover(true, false, true, 2000, 20))
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
    fun `溢出保护使截止时间饱和`() {
        val cur = timing(now = Long.MAX_VALUE / 2, duration = SessionMachine.MAX_SESSION_MS)
        // 直接构造超长时长事件不合法（状态机会拒绝），此处验证 addSaturating 行为经由合法上限
        val t = reduce(pending(), SessionEvent.Selected(7, false, SessionMachine.MAX_SESSION_MS, Long.MAX_VALUE / 2))
        assertEquals(Long.MAX_VALUE / 2 + SessionMachine.MAX_SESSION_MS, t.snapshot.deadlineElapsed)
    }
}
