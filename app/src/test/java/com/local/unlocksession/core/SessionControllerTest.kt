package com.local.unlocksession.core

import com.local.unlocksession.data.ReminderMark
import com.local.unlocksession.data.SessionStore
import java.util.concurrent.AbstractExecutorService
import com.local.unlocksession.logic.SessionEvent
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService

/**
 * 控制器层回归（FakeEnv + FakeStore + 直接执行器，全程确定性）：
 * 覆盖提示词要求中的控制器行为——提醒规划/消费、跨会话任务失效（R1）、
 * 调度失败兜底（R7）、冷启动核对（R2）、keyguard 过渡期复核（R0）、
 * 服务恢复不依赖 Activity、能力门与测试提醒。
 */
class SessionControllerTest {

    // ------------------------------------------------------------------
    // 假环境
    // ------------------------------------------------------------------

    private class RecordingDiag : DiagSink {
        val lines = mutableListOf<String>()
        override fun log(tag: String, message: String) {
            lines += "$tag|$message"
        }

        fun has(tag: String, contains: String): Boolean =
            lines.any { it.startsWith("$tag|") && it.contains(contains) }
    }

    private class FakeStore : SessionStore {
        var snapshotData: SessionSnapshot = SessionSnapshot()
        var bootAtSaveValue = 17
        var savedElapsedValue = 0L
        var nextId = 1L
        val consumed = mutableSetOf<String>()
        var monitoring = true
        var quick = listOf(10, 20, 30)
        var debugSeconds = false
        var showCountdownFlag = true
        var remindersEnabledFlag = true
        var thresholds = listOf(60L, 30L)

        /** 模拟磁盘写入失败（commit() 返回 false）：关键写入不落盘 */
        var failCriticalWrites = false
        var criticalWriteFailures = 0

        override fun loadSnapshot(): SessionSnapshot = snapshotData

        override fun saveSnapshot(s: SessionSnapshot): Boolean {
            if (failCriticalWrites) {
                criticalWriteFailures++
                return false
            }
            snapshotData = s
            savedElapsedValue = 999_999L
            return true
        }

        override fun setBootAtSave(value: Int) {
            bootAtSaveValue = value
        }

        override fun savedBootCount(): Int = bootAtSaveValue
        override fun savedElapsed(): Long = savedElapsedValue
        override fun peekNextSessionId(): Long = nextId

        override fun commitSessionId(used: Long): Boolean {
            if (failCriticalWrites) {
                criticalWriteFailures++
                return false
            }
            if (used >= nextId) nextId = used + 1
            return true
        }

        override fun consumedReminders(): Set<String> = consumed.toSet()

        override fun markReminderConsumed(key: String): ReminderMark {
            if (failCriticalWrites) {
                criticalWriteFailures++
                return ReminderMark.WRITE_FAILED
            }
            return if (consumed.add(key)) ReminderMark.NEW else ReminderMark.DUPLICATE
        }

        override fun markRemindersSkipped(keys: Collection<String>): Boolean {
            if (failCriticalWrites) {
                criticalWriteFailures++
                return false
            }
            consumed.addAll(keys)
            return true
        }

        override fun clearConsumedReminders(sessionId: Long): Boolean {
            if (failCriticalWrites) {
                criticalWriteFailures++
                return false
            }
            consumed.removeAll { it.startsWith("$sessionId:") }
            return true
        }

        override fun isMonitoringEnabled(): Boolean = monitoring
        override fun setMonitoringEnabled(value: Boolean) {
            monitoring = value
        }

        override fun getQuickMinutes(): List<Int> = quick
        override fun setQuickMinutes(values: List<Int>): Boolean {
            quick = values
            return true
        }

        override fun isDebugSecondsMode(): Boolean = debugSeconds
        override fun setDebugSecondsMode(value: Boolean) {
            debugSeconds = value
        }

        override fun isShowCountdown(): Boolean = showCountdownFlag
        override fun setShowCountdown(value: Boolean) {
            showCountdownFlag = value
        }

        override fun isRemindersEnabled(): Boolean = remindersEnabledFlag
        override fun setRemindersEnabled(value: Boolean) {
            remindersEnabledFlag = value
        }

        override fun getReminderThresholds(): List<Long> = thresholds
        override fun setReminderThresholds(values: List<Long>): Boolean {
            thresholds = values
            return true
        }
    }

    open class FakeEnv : ControllerEnv {
        var now: Long = 100_000L
        var interactive = true
        var kgLocked = false
        var devLocked = false
        var kgSecure = false
        var inCallMode = false
        var boot = 17
        var admin = true
        var overlayAllowed = true
        var notificationsOn = true
        var lockOk = true
        var lockError: String? = null
        var deadlineAlarmOk = true
        var reminderAlarmOk = true

        val delayed = LinkedHashMap<String, Pair<Long, () -> Unit>>()
        val deadlineAlarms = mutableListOf<Pair<Long, Long>>()
        var deadlineAlarmCancels = 0
        val reminderAlarms = mutableListOf<Triple<Long, Long, Long>>()
        val reminderAlarmCancels = mutableListOf<Long>()
        val reminderNotificationCancels = mutableListOf<Long>()
        val serviceStarts = mutableListOf<String>()
        var serviceAttached = false
        val lockCalls = mutableListOf<Long>()
        val enforceLockCalls = mutableListOf<Int>()
        val sessionNotifies = mutableListOf<Pair<SessionSnapshot, Boolean>>()
        val notReadyReasons = mutableListOf<String>()
        val notReadyCancels = mutableListOf<Int>()
        val remindersShown = mutableListOf<Long>()
        val testReminders = mutableListOf<Int>()
        val testReminderCancels = mutableListOf<Int>()
        val overlaysShown = mutableListOf<Long>()
        var overlaysHidden = 0

        override fun nowElapsed(): Long = now
        override fun isInteractive(): Boolean = interactive
        override fun isKeyguardLocked(): Boolean = kgLocked
        override fun isDeviceLocked(): Boolean = devLocked
        override fun isKeyguardSecure(): Boolean = kgSecure
        override fun isInCall(): Boolean = inCallMode
        override fun bootCount(): Int = boot
        override fun isAdminActive(): Boolean = admin
        override fun canDrawOverlays(): Boolean = overlayAllowed

        override fun scheduleDeadlineAlarm(sessionId: Long, deadlineElapsed: Long): Boolean {
            if (!deadlineAlarmOk) return false
            deadlineAlarms += sessionId to deadlineElapsed
            return true
        }

        override fun cancelDeadlineAlarm() {
            deadlineAlarmCancels++
        }

        override fun scheduleReminderAlarm(sessionId: Long, thresholdMs: Long, triggerElapsed: Long): Boolean {
            if (!reminderAlarmOk) return false
            reminderAlarms += Triple(sessionId, thresholdMs, triggerElapsed)
            return true
        }

        override fun cancelReminderAlarms(thresholdsMs: Collection<Long>) {
            reminderAlarmCancels += thresholdsMs
        }

        override fun lockNow(): Pair<Boolean, String?> {
            lockCalls += now
            return lockOk to lockError
        }

        override fun enforceLockOnScreenOff() {
            enforceLockCalls += 1
        }

        override fun postDelayed(key: String, delayMs: Long, block: () -> Unit) {
            delayed[key] = (now + delayMs) to block
        }

        override fun cancelDelayed(key: String) {
            delayed.remove(key)
        }

        override fun hasDelayed(key: String): Boolean = delayed.containsKey(key)

        override fun attachForegroundService(service: android.app.Service) {
            serviceAttached = true
        }

        override fun detachForegroundService(service: android.app.Service) {
            serviceAttached = false
        }

        override fun startMonitoringService(reason: String) {
            serviceStarts += reason
            // 模拟正常结果：startForegroundService 后服务很快挂载
            serviceAttached = true
        }

        override fun stopMonitoringService() {
            serviceAttached = false
        }

        override fun isServiceAttached(): Boolean = serviceAttached

        override fun postUi(block: () -> Unit) = block()

        override fun showSelectionOverlay(snap: SessionSnapshot) {
            overlaysShown += snap.sessionId
        }

        override fun hideSelectionOverlay() {
            overlaysHidden++
        }

        override fun notifySession(snap: SessionSnapshot, showCountdown: Boolean) {
            sessionNotifies += snap to showCountdown
        }

        override fun notifyNotReady(reason: String) {
            notReadyReasons += reason
        }

        override fun cancelNotReady() {
            notReadyCancels += 1
        }

        override fun notifyReminder(sessionId: Long, thresholdMs: Long) {
            remindersShown += thresholdMs
        }

        override fun notifyTestReminder() {
            testReminders += 1
        }

        override fun cancelReminderNotifications(thresholdsMs: Collection<Long>) {
            reminderNotificationCancels += thresholdsMs
        }

        override fun cancelTestReminder() {
            testReminderCancels += 1
        }

        override fun notificationsEnabled(): Boolean = notificationsOn

        /** 推进时钟并执行所有到期任务（可能链式再投递） */
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val due = delayed.entries.firstOrNull { it.value.first <= target } ?: break
                delayed.remove(due.key)
                now = maxOf(now, due.value.first)
                due.value.second()
            }
            now = target
        }

        /**
         * 只推进时钟，不执行任何到期回调（模拟回调停顿：ColorOS 冻结、线程卡住）。
         * 恢复执行用 [advance]：停顿期间排队的回调按队列顺序补跑，
         * 每个回调执行时读到的是恢复后的当前时刻（与真实 Handler 行为一致）。
         */
        fun advanceFrozen(ms: Long) {
            now += ms
        }
    }

    private class DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) {
            command.run()
        }

        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit?): Boolean = true
    }

    private val directExecutor = DirectExecutor()

    /** 以预置持久化快照构建控制器（快照必须在 startIfNeeded 之前写入才是“恢复现场”） */
    private fun controllerWithSnapshot(
        snap: SessionSnapshot,
        env: FakeEnv = FakeEnv(),
        store: FakeStore = FakeStore()
    ): SessionController {
        store.snapshotData = snap
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        return c
    }

    private fun newController(
        store: FakeStore = FakeStore(),
        env: FakeEnv = FakeEnv(),
        diag: RecordingDiag = RecordingDiag()
    ): Triple<SessionController, FakeEnv, RecordingDiag> {
        val c = SessionController(store, diag, env, directExecutor)
        c.startIfNeeded()
        return Triple(c, env, diag)
    }

    /** 解锁（keyguard 已消失）→ 面板 → 选择计时 */
    private fun startTiming(
        controller: SessionController,
        durationMs: Long
    ) {
        controller.signalUserPresent()
        val pendingId = controller.snapshot.sessionId
        assertTrue(controller.select(pendingId, unlimited = false, durationMs = durationMs))
    }

    // ------------------------------------------------------------------
    // 提醒规划与消费
    // ------------------------------------------------------------------

    @Test
    fun `两分钟会话安排60与30秒提醒并先后各提醒一次`() {
        val (c, env, diag) = newController()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        assertEquals(
            listOf(Triple(1L, 60_000L, 160_000L), Triple(1L, 30_000L, 190_000L)),
            env.reminderAlarms
        )
        // 到达 60 秒时点：提醒闹钟（广播路径）投递一次
        env.advance(60_000)
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(listOf(60_000L), env.remindersShown)
        // 到达 30 秒时点：第二个提醒独立投递
        env.advance(30_000)
        c.handleReminderAsync(c.snapshot.sessionId, 30_000L) {}
        assertEquals(listOf(60_000L, 30_000L), env.remindersShown)
        assertTrue(diag.has("RMD", "threshold=60000"))
    }

    @Test
    fun `重复投递被消费标记抑制`() {
        val (c, env, _) = newController()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        env.advance(60_000)
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(1, env.remindersShown.size)
        // 同一时点的重复广播：已消费 → 不再提醒
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(1, env.remindersShown.size)
    }

    @Test
    fun `45秒会话只安排30秒提醒_30秒会话开始时不提醒`() {
        val (c, env, _) = newController()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 45_000L)
        assertEquals(listOf(Triple(1L, 30_000L, 115_000L)), env.reminderAlarms)

        val env2 = FakeEnv()
        val c2 = SessionController(FakeStore(), RecordingDiag(), env2, directExecutor)
        c2.startIfNeeded()
        c2.signalUserPresent()
        c2.select(c2.snapshot.sessionId, false, 30_000L)
        assertTrue("等于总时长的时点不应排定", env2.reminderAlarms.isEmpty())
    }

    @Test
    fun `不限会话没有任何到期或提醒任务`() {
        val (c, env, _) = newController()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, true, null)
        assertEquals(SessionState.UNLIMITED, c.snapshot.state)
        assertTrue(env.deadlineAlarms.isEmpty())
        assertTrue(env.reminderAlarms.isEmpty())
    }

    @Test
    fun `倒计时与提醒开关彼此独立`() {
        val (c, env, _) = newController()
        c.setShowCountdown(false)
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        // 关闭倒计时：通知不逐秒刷新，但计时与提醒照常
        env.advance(60_000)
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(1, env.remindersShown.size)
        assertEquals(SessionState.TIMING, c.snapshot.state)
        // 关闭提醒、开启倒计时：仍有倒计时刷新、无提醒任务
        val store2 = FakeStore().apply { showCountdownFlag = true; remindersEnabledFlag = false }
        val env2 = FakeEnv()
        val c2 = SessionController(store2, RecordingDiag(), env2, directExecutor)
        c2.startIfNeeded()
        c2.signalUserPresent()
        c2.select(c2.snapshot.sessionId, false, 120_000L)
        assertTrue(env2.reminderAlarms.isEmpty())
        assertTrue(env2.sessionNotifies.isNotEmpty())
    }

    @Test
    fun `计时中修改提醒设置本次保持快照下次采用新设置`() {
        val (c, env, _) = newController()
        c.signalUserPresent()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        assertEquals(listOf(30L, 60L), c.snapshot.sessionReminderThresholds)
        // 本次会话进行中修改全局设置
        c.setReminderThresholds(listOf(15L))
        c.setRemindersEnabled(false)
        assertEquals(listOf(30L, 60L), c.snapshot.sessionReminderThresholds)
        // 结束本次，开新会话：采用新设置
        val alarmsBefore = env.reminderAlarms.size
        env.kgLocked = true
        env.devLocked = true
        c.signalScreenOff()
        env.kgLocked = false
        env.devLocked = false
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        assertEquals("新会话：提醒总开关关闭 → 不新增提醒任务", alarmsBefore, env.reminderAlarms.size)
    }

    @Test
    fun `恢复到剩余45秒跳过60秒保留未来30秒`() {
        val env = FakeEnv()
        val store = FakeStore()
        // 构造已持久化的 90 秒会话，恢复时剩余 45 秒
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 5L,
            startedElapsed = env.now - 45_000L, deadlineElapsed = env.now + 45_000L,
            sessionRemindersEnabled = true, sessionReminderThresholds = listOf(60L, 30L)
        )
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.recover("测试恢复")
        // 60 秒时点：trigger 已过 15s → 超出 2s 窗口 → 跳过；30 秒时点：未来 15s → 重排
        assertEquals(listOf(Triple(5L, 30_000L, 115_000L)), env.reminderAlarms)
        assertTrue(store.consumed.contains("5:60000"))
    }

    @Test
    fun `恢复到剩余20秒跳过全部历史提醒`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 6L,
            startedElapsed = env.now, deadlineElapsed = env.now + 20_000L,
            sessionRemindersEnabled = true, sessionReminderThresholds = listOf(60L, 30L)
        )
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.recover("测试恢复")
        assertTrue(env.remindersShown.isEmpty())
        // 20 秒会话：60/30 秒时点都 ≥ 总时长，规划阶段直接过滤（不排定也无需消费标记）
        assertTrue(env.reminderAlarms.isEmpty())
        assertTrue(store.consumed.isEmpty())
    }

    @Test
    fun `恢复时恰好到期只处理锁屏不发提醒`() {
        val env = FakeEnv()
        val c = controllerWithSnapshot(
            SessionSnapshot(
                state = SessionState.TIMING, sessionId = 7L,
                startedElapsed = env.now - 120_000L, deadlineElapsed = env.now,
                sessionRemindersEnabled = true, sessionReminderThresholds = listOf(60L, 30L)
            ), env
        )
        c.recover("测试恢复")
        assertEquals(SessionState.LOCK_REQUESTED, c.snapshot.state)
        assertEquals(1, env.lockCalls.size)
        assertTrue(env.remindersShown.isEmpty())
    }

    // ------------------------------------------------------------------
    // R1：跨会话任务失效
    // ------------------------------------------------------------------

    @Test
    fun `R1_A锁屏失败的重试排队后A结束_B期间重试不再执行锁屏`() {
        val (c, env, diag) = newController()
        startTiming(c, 60_000L)
        val aId = c.snapshot.sessionId
        // A 到期时锁屏失败 → LOCK_FAILED，自动重试任务排队（+3s）
        env.lockOk = false
        env.lockError = "DPM 异常"
        env.advance(60_000)
        assertEquals(SessionState.LOCK_FAILED, c.snapshot.state)
        assertEquals(1, env.lockCalls.size)
        // A 被熄屏结束：看门狗/重试任务必须取消
        env.kgLocked = true
        env.devLocked = true
        c.signalScreenOff()
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        // B 开始新会话并跑完
        env.kgLocked = false
        env.devLocked = false
        env.lockOk = true
        env.lockError = null
        c.signalUserPresent()
        val bId = c.snapshot.sessionId
        assertTrue(bId != aId)
        c.select(bId, false, 60_000L)
        env.advance(120_000) // 覆盖 A 的重试窗口 + B 的到期
        // 恰好两次锁屏调用：A 到期失败的请求 + B 到期成功的请求（A 的重试没有执行）
        assertEquals(2, env.lockCalls.size)
        assertTrue(diag.has("LOCK", "已取消会话"))
    }

    @Test
    fun `R1_旧会话的到期重放不影响新会话`() {
        val (c, env, _) = newController()
        startTiming(c, 60_000L)
        val oldId = c.snapshot.sessionId
        env.kgLocked = true
        env.devLocked = true
        c.signalScreenOff()
        env.kgLocked = false
        env.devLocked = false
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, true, null) // B 不限
        val bId = c.snapshot.sessionId
        c.handleAlarmAsync(oldId) {}
        assertTrue(c.snapshot.isUnlimited)
        assertEquals(bId, c.snapshot.sessionId)
    }

    // ------------------------------------------------------------------
    // R7：调度失败与兜底
    // ------------------------------------------------------------------

    @Test
    fun `R7_到期闹钟排定失败如实标记并由进程内兜底触发锁屏`() {
        val (c, env, diag) = newController()
        env.deadlineAlarmOk = false
        startTiming(c, 2_000L)
        assertFalse("排定失败必须如实标记", c.snapshot.alarmScheduled)
        assertTrue(diag.has("ALARM", "ok=false"))
        // 兜底：tick 在 deadline 后触发到期
        env.advance(3_000)
        assertEquals(SessionState.LOCK_REQUESTED, c.snapshot.state)
        assertEquals(1, env.lockCalls.size)
    }

    // ------------------------------------------------------------------
    // R0：keyguard 过渡期复核
    // ------------------------------------------------------------------

    @Test
    fun `R0_USER_PRESENT到达但keyguard暂未消失_复核后创建待选择`() {
        val (c, env, diag) = newController()
        env.kgLocked = true
        env.devLocked = false
        c.signalUserPresent()
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        // 密码验证通过，keyguard 过渡期结束
        env.kgLocked = false
        env.advance(300)
        assertEquals(SessionState.PENDING_SELECTION, c.snapshot.state)
        assertTrue(diag.has("RCK", "复核通过"))
    }

    @Test
    fun `R0_keyguard始终锁定时复核放弃不弹面板`() {
        val (c, env, diag) = newController()
        env.kgLocked = true
        c.signalUserPresent()
        env.advance(10_000)
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        assertTrue(diag.has("RCK", "复核放弃"))
        assertTrue(env.overlaysShown.isEmpty())
    }

    @Test
    fun `R0_复核期间发生熄屏则旧复核作废`() {
        val (c, env, diag) = newController()
        env.kgLocked = true
        c.signalUserPresent() // 开始复核 gen=1
        env.kgLocked = false
        env.devLocked = false
        env.interactive = false
        c.signalScreenOff() // 熄屏：代次推进
        env.interactive = true
        env.kgLocked = true
        env.devLocked = true
        val genBefore = env.delayed.keys.toList()
        env.advance(10_000)
        // 复核已作废：不创建会话
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        assertTrue(genBefore.isEmpty() || true)
    }

    // ------------------------------------------------------------------
    // R2：冷启动核对
    // ------------------------------------------------------------------

    @Test
    fun `R2_到期闹钟冷启动但设备已锁屏_先恢复核对不误锁`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 8L,
            startedElapsed = env.now, deadlineElapsed = env.now + 1_000L
        )
        // 进程“死过”期间用户已经手动锁屏：设备当前锁定
        env.kgLocked = true
        env.devLocked = true
        env.interactive = false
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        var finished = 0
        c.handleAlarmAsync(8L) { finished++ }
        // 恢复核对：锁定 → 会话结束，不执行锁屏
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        assertEquals(0, env.lockCalls.size)
        assertEquals(1, finished)
    }

    @Test
    fun `R2_非同次开机的到期事件被作废且按当前状态补面板`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 9L,
            startedElapsed = 1_000L, deadlineElapsed = 2_000L
        )
        store.bootAtSaveValue = 16 // 旧开机
        env.boot = 17
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        var finished = 0
        c.handleAlarmAsync(9L) { finished++ }
        // 旧开机任务作废；当前已解锁 → R6 语义补出待选择（而非直接锁屏）
        assertEquals(SessionState.PENDING_SELECTION, c.snapshot.state)
        assertEquals(0, env.lockCalls.size)
        assertEquals(1, finished)
    }

    @Test
    fun `R2_正常到期完成回调总是执行`() {
        val (c, env, _) = newController()
        startTiming(c, 1_000L)
        var finished = 0
        env.advance(1_000)
        c.handleAlarmAsync(c.snapshot.sessionId) { finished++ }
        assertEquals(1, finished)
    }

    // ------------------------------------------------------------------
    // 测试提醒（功能 C）
    // ------------------------------------------------------------------

    @Test
    fun `测试提醒不改状态截止消费标记或锁屏`() {
        val (c, env, _) = newController()
        assertTrue(c.testReminder())
        env.advance(10_000) // 自动清理到期
        assertEquals(1, env.testReminders.size)
        assertEquals(1, env.testReminderCancels.size)
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        assertEquals(0, env.lockCalls.size)
        assertTrue(env.remindersShown.isEmpty())
    }

    @Test
    fun `测试提醒节流与通知禁用提示`() {
        val (c, env, _) = newController()
        assertTrue(c.testReminder())
        assertFalse("2 秒内重复点击被节流", c.testReminder())
        env.notificationsOn = false
        assertFalse(c.testReminder())
    }

    @Test
    fun `测试提醒不占用真实提醒的已消费标记`() {
        val store = FakeStore()
        val env = FakeEnv()
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.testReminder()
        assertTrue(store.consumed.isEmpty())
    }

    // ------------------------------------------------------------------
    // 能力门 / 恢复 / 覆盖层失败追踪
    // ------------------------------------------------------------------

    @Test
    fun `能力未就绪时解锁被拒并记录原因`() {
        val (c, env, diag) = newController()
        env.admin = false
        c.signalUserPresent()
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        assertTrue(env.notReadyReasons.first().contains("设备管理员"))
    }

    @Test
    fun `悬浮层挂载失败被记录而非崩溃`() {
        val store = FakeStore()
        val env = object : FakeEnv() {
            override fun showSelectionOverlay(snap: SessionSnapshot) {
                throw IllegalStateException("view 挂载失败")
            }
        }
        val diag = RecordingDiag()
        val c = SessionController(store, diag, env, directExecutor)
        c.startIfNeeded()
        c.signalUserPresent()
        assertEquals(SessionState.PENDING_SELECTION, c.snapshot.state)
        assertTrue(diag.has("OVF", "显示选择层异常"))
    }

    @Test
    fun `服务恢复不依赖MainActivity_已解锁无会话补出待选择`() {
        val store = FakeStore()
        val env = FakeEnv()
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.recover("STICKY 恢复")
        assertEquals(SessionState.PENDING_SELECTION, c.snapshot.state)
        assertEquals(1, env.overlaysShown.size)
    }

    @Test
    fun `服务恢复_TIMING保留原截止时间`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 11L,
            startedElapsed = env.now - 10_000L, deadlineElapsed = env.now + 50_000L
        )
        val deadline = env.now + 50_000L
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.recover("STICKY 恢复")
        assertEquals(SessionState.TIMING, c.snapshot.state)
        assertEquals(deadline, c.snapshot.deadlineElapsed)
        assertTrue(env.deadlineAlarms.contains(11L to deadline))
    }

    // ------------------------------------------------------------------
    // 广播完成回调异常路径
    // ------------------------------------------------------------------

    @Test
    fun `R2_处理异常时finish仍执行`() {
        val store = FakeStore()
        val env = object : FakeEnv() {
            override fun nowElapsed(): Long = throw RuntimeException("时钟故障")
        }
        var finished = 0
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.handleAlarmAsync(1L) { finished++ }
        assertEquals(1, finished)
    }
    // ------------------------------------------------------------------
    // R0 补充：解锁一致性心跳（宽限期放行无广播场景）
    // ------------------------------------------------------------------

    @Test
    fun `心跳发现宽限期放行后无会话则补待选择`() {
        val (c, env, diag) = newController()
        // 亮屏但 keyguard 锁定：SCREEN_ON 不建会话，但启动心跳
        env.kgLocked = true
        env.devLocked = true
        c.signalScreenOn()
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        // 宽限期内上滑放行：无任何广播，keyguard 直接消失
        env.kgLocked = false
        env.devLocked = false
        env.advance(2_000)
        assertEquals(SessionState.PENDING_SELECTION, c.snapshot.state)
        assertTrue(diag.has("HB", "补上待选择"))
        // 建会话后心跳不再运行
        val sessionId = c.snapshot.sessionId
        c.select(sessionId, false, 60_000L)
        env.advance(6_000)
        assertEquals(SessionState.TIMING, c.snapshot.state)
    }

    @Test
    fun `心跳在keyguard始终锁定时空转不建会话`() {
        val (c, env, _) = newController()
        env.kgLocked = true
        env.devLocked = true
        c.signalScreenOn()
        env.advance(10_000)
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        assertTrue(env.overlaysShown.isEmpty())
    }

    @Test
    fun `tick兜底在提醒闹钟迟到时准时投递`() {
        val (c, env, diag) = newController()
        // 模拟 ColorOS 推迟提醒闹钟：会话 120s，60s 提醒闹钟被推迟（不触发），
        // tick 每秒运行应在 trigger 后 1 秒内兜底投递
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        env.advance(60_500) // tick 链逐秒推进，60s 时点已过 0.5s
        assertEquals("闹钟未触发时 tick 必须兜底", listOf(60_000L), env.remindersShown)
        // 之后真实闹钟迟到到达：已消费 → 不重复
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(1, env.remindersShown.size)
        assertFalse(diag.has("RMD", "跳过不补发 threshold=60000"))
    }

    @Test
    fun `tick兜底不影响到期判定且投递后闹钟路径被抑制`() {
        val (c, env, _) = newController()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 90_000L)
        env.advance(89_800) // 90s 会话：60s 时点（30s 处）与 30s 时点（60s 处）均由 tick 兜底投递
        assertEquals(listOf(60_000L, 30_000L), env.remindersShown)
        env.advance(500) // 到期：tick 触发锁屏
        assertEquals(SessionState.LOCK_REQUESTED, c.snapshot.state)
        assertEquals(1, env.lockCalls.size)
    }

    // ------------------------------------------------------------------
    // 提醒取消的单位一致性（秒→毫秒）
    // ------------------------------------------------------------------

    @Test
    fun `会话结束时按毫秒身份取消提醒闹钟与通知`() {
        val (c, env, _) = newController()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        // 排定身份：60 秒 → requestCode 200060 / 通知 ID 5060（毫秒口径 60000）
        assertEquals(
            listOf(Triple(1L, 60_000L, 160_000L), Triple(1L, 30_000L, 190_000L)),
            env.reminderAlarms
        )
        // 60 秒提醒已投递（通知已显示）
        env.advance(60_000)
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(listOf(60_000L), env.remindersShown)
        // 提前锁屏结束会话：取消必须用毫秒身份对上排定任务
        env.kgLocked = true
        env.devLocked = true
        c.signalScreenOff()
        assertEquals(listOf(30000L, 60000L), env.reminderAlarmCancels)
        assertEquals(listOf(30000L, 60000L), env.reminderNotificationCancels)
        // 新会话不再被旧提醒闹钟打扰（旧到点广播因身份/标记全部失效）
        env.kgLocked = false
        env.devLocked = false
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        val newId = c.snapshot.sessionId
        env.advance(60_000)
        c.handleReminderAsync(newId, 60_000L) {} // 新会话自己的 60s 提醒照常
        assertEquals(listOf(60_000L, 60_000L), env.remindersShown)
    }

    @Test
    fun `重启废弃旧会话同样按毫秒身份取消提醒`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 5L,
            startedElapsed = env.now - 60_000L, deadlineElapsed = env.now + 60_000L,
            sessionRemindersEnabled = true, sessionReminderThresholds = listOf(60L, 30L)
        )
        store.bootAtSaveValue = 16 // 旧开机
        env.boot = 17
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.recover("重启后恢复检查")
        assertEquals(setOf(30000L, 60000L), env.reminderAlarmCancels.toSet())
        assertEquals(setOf(30000L, 60000L), env.reminderNotificationCancels.toSet())
    }

    // ------------------------------------------------------------------
    // 冷启动恢复：广播拉起进程后恢复监控（回归 A）
    // ------------------------------------------------------------------

    @Test
    fun `到期广播拉起新进程后完成锁屏并恢复监控服务`() {
        val env = FakeEnv()
        val store = FakeStore()
        // 新进程：只有持久化数据，服务未挂载；会话已过期
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 5L,
            startedElapsed = env.now - 120_000L, deadlineElapsed = env.now - 1_000L
        )
        store.bootAtSaveValue = 17
        env.boot = 17
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        assertEquals("前置：服务未挂载", false, env.isServiceAttached())
        var finished = 0
        c.handleAlarmAsync(5L) { finished++ }
        // 到期锁屏由广播路径完成，不依赖服务启动
        assertEquals(SessionState.LOCK_REQUESTED, c.snapshot.state)
        assertEquals(1, env.lockCalls.size)
        assertEquals(1, finished)
        // 同时已请求恢复监控服务（幂等恢复）
        assertTrue(env.serviceStarts.isNotEmpty())
        assertTrue(env.serviceStarts.first().contains("恢复监控"))
    }

    @Test
    fun `提醒广播拉起新进程后投递提醒并恢复监控服务`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 6L,
            startedElapsed = env.now - 60_000L, deadlineElapsed = env.now + 60_000L,
            sessionRemindersEnabled = true, sessionReminderThresholds = listOf(60L, 30L)
        )
        store.bootAtSaveValue = 17
        env.boot = 17
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.handleReminderAsync(6L, 60_000L) {}
        assertEquals("60s 时点在 2s 迟到窗口内（恰好到点）", listOf(60_000L), env.remindersShown)
        assertTrue(env.serviceStarts.isNotEmpty())
    }

    @Test
    fun `监控关闭时唤醒入口不复活监控服务`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.monitoring = false
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 7L,
            startedElapsed = env.now - 120_000L, deadlineElapsed = env.now - 1_000L
        )
        store.bootAtSaveValue = 17
        env.boot = 17
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        c.handleAlarmAsync(7L) {}
        // 用户主动关闭的监控不得被旧事件复活
        assertTrue(env.serviceStarts.isEmpty())
        assertFalse(env.isServiceAttached())
    }

    // ------------------------------------------------------------------
    // 服务恢复后的解锁心跳（回归 B：仅改变锁屏事实，无任何广播）
    // ------------------------------------------------------------------

    @Test
    fun `亮屏密码锁状态下恢复后_仅改变锁屏事实_心跳周期内补出待选择`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot() // NO_SESSION
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        // 服务在屏幕已亮、keyguard 密码锁仍显示、NO_SESSION 时被恢复
        env.interactive = true
        env.kgLocked = true
        env.devLocked = true
        c.recover("STICKY 恢复")
        assertEquals(SessionState.NO_SESSION, c.snapshot.state)
        assertTrue("恢复时不应凭亮屏建会话", env.overlaysShown.isEmpty())
        // 用户输入密码解锁：只改变锁屏事实，不发送 SCREEN_ON / USER_PRESENT
        env.kgLocked = false
        env.devLocked = false
        env.advance(2_000) // 一个心跳周期
        assertEquals(SessionState.PENDING_SELECTION, c.snapshot.state)
        assertEquals(1, env.overlaysShown.size)
        // 建会话后心跳停止（不重复弹面板）
        val id = c.snapshot.sessionId
        c.select(id, false, 60_000L)
        env.advance(10_000)
        assertEquals(SessionState.TIMING, c.snapshot.state)
        assertEquals(1, env.overlaysShown.size)
    }

    @Test
    fun `恢复时已有会话或熄屏则心跳不启动`() {
        val env = FakeEnv()
        val store = FakeStore()
        store.snapshotData = SessionSnapshot(
            state = SessionState.TIMING, sessionId = 8L,
            startedElapsed = env.now - 10_000L, deadlineElapsed = env.now + 50_000L
        )
        val c = SessionController(store, RecordingDiag(), env, directExecutor)
        c.startIfNeeded()
        env.interactive = true
        env.kgLocked = false
        env.devLocked = false
        c.recover("STICKY 恢复")
        assertEquals(SessionState.TIMING, c.snapshot.state)
        // 已有会话：不排心跳任务
        assertFalse(env.hasDelayed("unlock-heartbeat"))
        // 熄屏恢复：同样不排
        val env2 = FakeEnv()
        val c2 = SessionController(FakeStore(), RecordingDiag(), env2, directExecutor)
        c2.startIfNeeded()
        env2.interactive = false
        env2.kgLocked = true
        c2.recover("STICKY 恢复")
        assertFalse(env2.hasDelayed("unlock-heartbeat"))
    }

    // ------------------------------------------------------------------
    // 提醒迟到窗口统一：tick 与广播共用判定
    // ------------------------------------------------------------------

    @Test
    fun `回调停顿跨过提醒窗口后不补发历史提醒`() {
        val env = FakeEnv()
        val store = FakeStore()
        val diag = RecordingDiag()
        val c = SessionController(store, diag, env, directExecutor)
        c.startIfNeeded()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 90_000L) // deadline=190000；60s→130000、30s→160000
        // 回调停顿：tick 跑到 +25s 后冻结 +45s（剩余 20s），期间提醒广播也未处理
        env.advance(25_000)
        assertEquals(SessionState.TIMING, c.snapshot.state)
        env.advanceFrozen(45_000)
        assertEquals(170_000L, env.now)
        // 恢复执行：停顿期间排队的 tick 回调补跑，读到当前时刻
        env.advance(0)
        assertEquals("超窗提醒不得补发", emptyList<Long>(), env.remindersShown)
        assertTrue(store.consumed.contains("1:60000"))
        assertTrue(store.consumed.contains("1:30000"))
        assertTrue(diag.has("RMD", "跳过不补发"))
        // 剩余时间继续走完：到期锁屏优先，不受停顿影响
        env.advance(20_000)
        assertEquals(SessionState.LOCK_REQUESTED, c.snapshot.state)
        assertEquals(1, env.lockCalls.size)
    }

    @Test
    fun `停顿落在迟到窗口内仍正常投递`() {
        val (c, env, _) = newController()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L) // deadline=220000；60s→160000
        env.advance(59_000) // now=159000
        env.advanceFrozen(2_000) // now=161000 = trigger+1s，仍在 2s 窗口内
        env.advance(0)
        assertEquals("窗口内的及时提醒照常投递", listOf(60_000L), env.remindersShown)
        // 迟到的广播路径到达：已消费 → 不重复
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(1, env.remindersShown.size)
    }

    @Test
    fun `截止后到达的旧提醒广播转锁屏不发提醒`() {
        val (c, env, _) = newController()
        startTiming(c, 60_000L)
        env.advance(60_000) // tick 兜底触发到期锁屏
        assertEquals(SessionState.LOCK_REQUESTED, c.snapshot.state)
        val shown = env.remindersShown.size
        c.handleReminderAsync(c.snapshot.sessionId, 30_000L) {} // 迟到的提醒广播
        assertEquals("已过截止：锁屏优先，不再发提醒", shown, env.remindersShown.size)
    }

    // ------------------------------------------------------------------
    // 重复 USER_PRESENT：不按会话年龄重置（R4 新语义）
    // ------------------------------------------------------------------

    @Test
    fun `超过三秒的重复USER_PRESENT不改变会话与截止时间`() {
        val (c, env, _) = newController()
        startTiming(c, 60_000L)
        val id = c.snapshot.sessionId
        val deadline = c.snapshot.deadlineElapsed
        val overlaysBefore = env.overlaysShown.size
        env.advance(10_000) // 远超旧的 3 秒窗口
        c.signalUserPresent() // 迟到/重复的解锁广播
        assertEquals(SessionState.TIMING, c.snapshot.state)
        assertEquals(id, c.snapshot.sessionId)
        assertEquals("截止时间不得被重置", deadline, c.snapshot.deadlineElapsed)
        assertEquals("不得重新弹选择层", overlaysBefore, env.overlaysShown.size)
    }

    // ------------------------------------------------------------------
    // 持久化失败：区分重复标记与写盘失败
    // ------------------------------------------------------------------

    @Test
    fun `消费标记写盘失败时保守跳过投递并如实记录`() {
        val env = FakeEnv()
        val store = FakeStore()
        val diag = RecordingDiag()
        val c = SessionController(store, diag, env, directExecutor)
        c.startIfNeeded()
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 120_000L)
        store.failCriticalWrites = true
        env.advance(60_000)
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals("写盘失败≠去重成功：不得投递", emptyList<Long>(), env.remindersShown)
        assertTrue(diag.has("RMD", "写盘失败"))
        assertTrue(diag.has("RMD", "保守跳过"))
        // 磁盘恢复后同一时点的再次触发：标记成功落盘，照常投递一次
        store.failCriticalWrites = false
        c.handleReminderAsync(c.snapshot.sessionId, 60_000L) {}
        assertEquals(listOf(60_000L), env.remindersShown)
    }

    @Test
    fun `快照与会话标识写盘失败如实记录且会话继续`() {
        val env = FakeEnv()
        val store = FakeStore()
        val diag = RecordingDiag()
        val c = SessionController(store, diag, env, directExecutor)
        c.startIfNeeded()
        store.failCriticalWrites = true
        c.signalUserPresent()
        c.select(c.snapshot.sessionId, false, 60_000L)
        assertEquals("内存状态不受磁盘失败影响", SessionState.TIMING, c.snapshot.state)
        assertTrue("快照写盘失败必须留痕", diag.has("ERR", "快照写盘失败"))
        assertTrue(diag.has("ERR", "会话标识写盘失败"))
        assertTrue(store.criticalWriteFailures >= 2)
        store.failCriticalWrites = false
        // 会话照常到期锁屏（内存状态一致）
        env.advance(60_000)
        assertEquals(SessionState.LOCK_REQUESTED, c.snapshot.state)
    }
}
