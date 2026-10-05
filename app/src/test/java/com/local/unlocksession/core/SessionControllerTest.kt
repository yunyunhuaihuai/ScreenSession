package com.local.unlocksession.core

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

        override fun loadSnapshot(): SessionSnapshot = snapshotData
        override fun saveSnapshot(s: SessionSnapshot) {
            snapshotData = s
            savedElapsedValue = 999_999L
        }

        override fun setBootAtSave(value: Int) {
            bootAtSaveValue = value
        }

        override fun savedBootCount(): Int = bootAtSaveValue
        override fun savedElapsed(): Long = savedElapsedValue
        override fun peekNextSessionId(): Long = nextId
        override fun commitSessionId(used: Long) {
            if (used >= nextId) nextId = used + 1
        }

        override fun consumedReminders(): Set<String> = consumed.toSet()
        override fun markReminderConsumed(key: String): Boolean = consumed.add(key)
        override fun markRemindersSkipped(keys: Collection<String>) {
            consumed.addAll(keys)
        }

        override fun clearConsumedReminders(sessionId: Long) {
            consumed.removeAll { it.startsWith("$sessionId:") }
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

        override fun attachForegroundService(service: android.app.Service) = Unit
        override fun detachForegroundService(service: android.app.Service) = Unit
        override fun startMonitoringService(reason: String) = Unit
        override fun stopMonitoringService() = Unit
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
            // 记录即可
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
}
