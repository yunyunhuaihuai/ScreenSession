package com.local.unlocksession.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.local.unlocksession.data.SessionRepository
import com.local.unlocksession.diag.Diagnostics
import com.local.unlocksession.lock.LockController
import com.local.unlocksession.logic.DurationInput
import com.local.unlocksession.logic.SessionEvent
import com.local.unlocksession.logic.SessionAction
import com.local.unlocksession.logic.SessionMachine
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState
import com.local.unlocksession.overlay.SelectionOverlay
import com.local.unlocksession.service.SessionAlarmReceiver
import com.local.unlocksession.service.UnlockMonitorService
import com.local.unlocksession.session.SessionPanelBridge
import com.local.unlocksession.util.Format
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 会话控制器：把 UI、广播、服务恢复、闹钟事件统一汇入单线程串行处理，
 * 是状态机唯一的调用方和所有副作用的唯一执行方。
 *
 * 事件顺序：单线程执行器保证 FIFO；ScreenOff/UserPresent 等信号的设备事实
 * （是否交互、keyguard 是否锁定、是否通话中）在处理时刻读取，避免广播陈旧值。
 */
class SessionController(
    private val appContext: Context,
    private val repo: SessionRepository,
    private val diag: Diagnostics
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-controller").apply { isDaemon = false }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifier = SessionNotifier(appContext)
    private val panelBridge = SessionPanelBridge(this)

    @Volatile
    var snapshot: SessionSnapshot = SessionSnapshot()
        private set

    /** debug 构建重放“旧会话到期事件”用：上一次会话的标识 */
    @Volatile
    var previousSessionId: Long = 0L
        private set

    private var started = false
    private var lockRetries = 0
    private var watchdogToken: Runnable? = null
    private var tickToken: Runnable? = null

    private val uiListeners = CopyOnWriteArrayList<(SessionSnapshot) -> Unit>()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun startIfNeeded() {
        if (started) return
        started = true
        diag.attachContext(appContext)
        snapshot = repo.loadSnapshot()
        notifier.ensureChannels()
        diag.log("INIT", "控制器启动: ${Diagnostics.deviceInfoLine(appContext)} 恢复快照=${snapshot}")
    }

    fun attachService(service: android.app.Service) {
        startIfNeeded()
        notifier.attach(service)
    }

    /** 服务 onCreate/onStartCommand 时立即刷新前台通知（满足 5 秒内 startForeground 要求） */
    fun refreshServiceNotification() {
        startIfNeeded()
        notifier.update(snapshot)
    }

    fun detachService(service: android.app.Service) {
        notifier.detach()
        diag.log("SVC", "前台服务 detached（系统可能稍后重启服务）")
    }

    fun addUiListener(listener: (SessionSnapshot) -> Unit) {
        uiListeners.add(listener)
        mainHandler.post { listener(snapshot) }
    }

    fun removeUiListener(listener: (SessionSnapshot) -> Unit) {
        uiListeners.remove(listener)
    }

    // ------------------------------------------------------------------
    // 对外信号（任意线程调用，内部串行）
    // ------------------------------------------------------------------

    fun signalScreenOn() = submit {
        startIfNeeded()
        val ev = SessionEvent.ScreenOn(
            interactive = isInteractive(),
            keyguardLocked = LockController.isKeyguardLocked(appContext),
            nextSessionId = repo.peekNextSessionId()
        )
        if (gateIfNeeded(ev)) return@submit
        process(ev)
    }

    fun signalScreenOff() = submit {
        startIfNeeded()
        val ev = SessionEvent.ScreenOff(
            keyguardSecure = LockController.isKeyguardSecure(appContext),
            inCall = isInCall()
        )
        process(ev)
    }

    fun signalUserPresent() = submit {
        startIfNeeded()
        val ev = SessionEvent.UserPresent(
            keyguardLocked = LockController.isKeyguardLocked(appContext),
            nextSessionId = repo.peekNextSessionId()
        )
        if (gateIfNeeded(ev)) return@submit
        process(ev)
    }

    fun signalAlarm(firedSessionId: Long) = submit {
        startIfNeeded()
        process(SessionEvent.AlarmFired(firedSessionId, SystemClock.elapsedRealtime()))
    }

    /**
     * 恢复检查：开机、服务重启、进程恢复、应用更新后调用。
     * 服务可能在首次解锁广播之后才启动，必须主动核对当前状态补上选择界面。
     */
    fun recover(reason: String) = submit {
        startIfNeeded()
        val interactive = isInteractive()
        val keyguardLocked = LockController.isKeyguardLocked(appContext)
        val sameBoot = computeSameBoot()
        diag.log(
            "RECOVER",
            "原因=$reason interactive=$interactive keyguardLocked=$keyguardLocked " +
                "sameBoot=$sameBoot (savedBoot=${repo.savedBootCount()} currentBoot=${Diagnostics.bootCount(appContext)} " +
                "savedElapsed=${repo.savedElapsed()} nowElapsed=${SystemClock.elapsedRealtime()}) " +
                "persisted=${snapshot}"
        )
        val ev = SessionEvent.Recover(
            interactive = interactive,
            keyguardLocked = keyguardLocked,
            sameBoot = sameBoot,
            nowElapsed = SystemClock.elapsedRealtime(),
            nextSessionId = repo.peekNextSessionId()
        )
        if (gateIfNeeded(ev)) return@submit
        process(ev)
    }

    /** 用户做出选择。输入非法返回 false（不产生事件）；选择从确认时刻开始计时 */
    fun select(pendingSessionId: Long, unlimited: Boolean, durationMs: Long?): Boolean {
        if (!unlimited && durationMs == null) return false
        startIfNeeded()
        submit {
            process(
                SessionEvent.Selected(
                    pendingSessionId, unlimited, durationMs ?: 0L, SystemClock.elapsedRealtime()
                )
            )
        }
        return true
    }

    // ------------------------------------------------------------------
    // 配置与调试
    // ------------------------------------------------------------------

    /** 监控开关。会话进行中不允许关闭（防间接取消计时） */
    fun setMonitoring(enabled: Boolean): Boolean {
        startIfNeeded()
        val cur = snapshot
        if (!enabled && (cur.isActive || cur.wantsOverlay)) {
            diag.log("CFG", "拒绝关闭监控：会话进行中 state=${cur.state}")
            return false
        }
        repo.setMonitoringEnabled(enabled)
        diag.log("CFG", "监控开关 → $enabled")
        if (enabled) {
            startMonitoringService()
        } else {
            notifier.update(clearedSnapshot())
            stopMonitoringService()
        }
        return true
    }

    fun quickMinutes(): List<Int> = repo.getQuickMinutes()

    fun quickLabels(): List<String> = repo.getQuickMinutes().map { Format.minutesLabel(it) }

    /** UI 读取监控开关（不做会话判断） */
    fun isMonitoringEnabledForUi(): Boolean = repo.isMonitoringEnabled()

    /** 保存三个快捷时长（分钟）。只影响之后的新会话，不改变已启动的截止时间 */
    fun setQuickMinutesPublic(values: List<Int>): Boolean {
        val ok = repo.setQuickMinutes(values)
        if (ok) diag.log("CFG", "快捷时长已更新：$values（当前会话不受影响）")
        return ok
    }

    fun isDebugSecondsMode(): Boolean = repo.isDebugSecondsMode()

    fun setDebugSecondsMode(value: Boolean) = repo.setDebugSecondsMode(value)

    /** debug：重放一个到期事件，用于验证旧事件失效与幂等 */
    fun debugReplayAlarm(sessionId: Long) {
        check(com.local.unlocksession.BuildConfig.DEBUG) { "仅 debug 构建可用" }
        diag.log("DEBUG", "重放到期事件 sessionId=$sessionId")
        signalAlarm(sessionId)
    }

    /** debug：手动触发一次恢复检查 */
    fun debugRecover() {
        check(com.local.unlocksession.BuildConfig.DEBUG) { "仅 debug 构建可用" }
        recover("debug 手动触发")
    }

    // ------------------------------------------------------------------
    // 面板桥接（悬浮层 / 兜底 Activity 共用）
    // ------------------------------------------------------------------

    internal fun panelBridge(): com.local.unlocksession.session.SessionPanelBridge = panelBridge

    internal fun submitSelection(pendingSessionId: Long, unlimited: Boolean, durationMs: Long?) =
        select(pendingSessionId, unlimited, durationMs)

    // ------------------------------------------------------------------
    // 内部：串行处理
    // ------------------------------------------------------------------

    private fun submit(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                diag.log("ERR", "事件处理异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun process(ev: SessionEvent) {
        val from = snapshot
        val t = SessionMachine.reduce(from, ev)
        val changed = t.snapshot != from || t.actions.isNotEmpty()
        if (!changed) {
            diag.log(
                "EVT",
                "${label(ev)} → 无变化 state=${from.state}(${from.sessionId})"
                    .let { if (t.note != null) "$it note=${t.note}" else it }
            )
            return
        }
        snapshot = t.snapshot
        if (t.snapshot.sessionId != from.sessionId) {
            if (from.sessionId != 0L) previousSessionId = from.sessionId
            if (t.snapshot.sessionId != 0L) repo.commitSessionId(t.snapshot.sessionId)
        }
        if (t.snapshot.state != SessionState.LOCK_REQUESTED && t.snapshot.state != SessionState.LOCK_FAILED) {
            lockRetries = 0
        }
        diag.log(
            "EVT",
            "${label(ev)}: ${from.state}(${from.sessionId}) → ${t.snapshot.state}(${t.snapshot.sessionId})"
                .let { if (t.note != null) "$it note=${t.note}" else it }
        )
        execute(t.actions, t.snapshot)
        publish()
        scheduleNotificationTick()
        maybeScheduleLockRetry(t.snapshot.state)
    }

    private fun execute(actions: List<SessionAction>, snap: SessionSnapshot) {
        for (a in actions) {
            try {
                when (a) {
                    SessionAction.Persist -> {
                        repo.setBootAtSave(Diagnostics.bootCount(appContext))
                        repo.saveSnapshot(snap)
                    }

                    SessionAction.ShowSelectionOverlay -> showSelectionOverlay(snap)
                    SessionAction.HideSelectionOverlay -> mainHandler.post { SelectionOverlay.hide() }

                    is SessionAction.ScheduleAlarm -> scheduleAlarm(a.sessionId, a.deadlineElapsed)
                    SessionAction.CancelAlarm -> cancelAlarm()

                    SessionAction.RequestLockNow -> performLockNow()
                    SessionAction.RequestLockOnScreenOff -> performLockOnScreenOff()

                    SessionAction.UpdateNotification -> notifier.update(snap)
                    SessionAction.ShowNotReadyNotification -> notifier.showNotReady("功能未就绪")

                    is SessionAction.ScheduleLockWatchdog -> scheduleWatchdog(a.delayMs)
                    SessionAction.CancelLockWatchdog -> cancelWatchdog()
                }
            } catch (e: Exception) {
                diag.log("ERR", "执行动作 $a 异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** 会话创建门槛：监控关闭 / 管理员未启用 / 悬浮窗未授权时不允许进入待选择，如实提醒 */
    private fun gateIfNeeded(ev: SessionEvent): Boolean {
        if (snapshot.state != SessionState.NO_SESSION) return false
        val missing = capabilityGate()
        if (missing == null) {
            notifier.clearNotReady()
            return false
        }
        diag.log("GATE", "事件 ${label(ev)} 被拒：$missing")
        notifier.showNotReady(missing)
        return true
    }

    private fun capabilityGate(): String? {
        if (!repo.isMonitoringEnabled()) return "监控已关闭"
        if (!LockController.isAdminActive(appContext)) return "设备管理员未启用"
        if (!Settings.canDrawOverlays(appContext)) return "悬浮窗权限未授予"
        return null
    }

    private fun showSelectionOverlay(snap: SessionSnapshot) {
        if (!Settings.canDrawOverlays(appContext)) {
            diag.log("OVF", "无法显示选择层：悬浮窗权限缺失")
            notifier.showNotReady("悬浮窗权限未授予")
            return
        }
        mainHandler.post {
            try {
                SelectionOverlay.show(
                    appContext,
                    snap.sessionId,
                    quickLabels(),
                    repo.isDebugSecondsMode(),
                    panelBridge
                )
                diag.log("OVF", "已请求显示选择层 sessionId=${snap.sessionId}")
            } catch (e: Exception) {
                diag.log("OVF", "显示选择层异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun scheduleAlarm(sessionId: Long, deadlineElapsed: Long) {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadlineElapsed,
                alarmPendingIntent(sessionId)
            )
            diag.log(
                "ALARM",
                "已设定到期闹钟 sessionId=$sessionId deadline=$deadlineElapsed " +
                    "剩余=${deadlineElapsed - SystemClock.elapsedRealtime()}ms"
            )
        } catch (e: Exception) {
            diag.log("ALARM", "设定闹钟失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun cancelAlarm() {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.cancel(alarmPendingIntent(0L))
            diag.log("ALARM", "已取消到期闹钟")
        } catch (e: Exception) {
            diag.log("ALARM", "取消闹钟异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun alarmPendingIntent(sessionId: Long): PendingIntent {
        val intent = Intent(appContext, SessionAlarmReceiver::class.java)
            .setAction(SessionAlarmReceiver.ACTION_SESSION_ALARM)
            .putExtra(SessionAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 到期锁屏：先进入 LOCK_REQUESTED 再调用 lockNow()，结果回传状态机 */
    private fun performLockNow() {
        val (ok, err) = LockController.requestLock(appContext, diag)
        submit {
            process(SessionEvent.LockOutcome(ok, err, SystemClock.elapsedRealtime()))
        }
    }

    /** 提前熄屏结束会话时加强 keyguard，消除系统解锁宽限期（仅在系统有安全凭据时有效） */
    private fun performLockOnScreenOff() {
        if (!LockController.isAdminActive(appContext)) {
            diag.log("LOCK", "熄屏加强锁定跳过：管理员未启用")
            return
        }
        if (!LockController.isKeyguardSecure(appContext)) {
            diag.log("LOCK", "熄屏加强锁定跳过：系统未设置安全锁屏凭据")
            return
        }
        try {
            val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            dpm.lockNow()
            diag.log("LOCK", "熄屏后已调用 lockNow() 加强 keyguard（防宽限期）")
        } catch (e: Exception) {
            diag.log("LOCK", "熄屏加强锁定异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 锁屏失败后自动重试一次（3 秒后），仍失败则保持失败状态并记录 */
    private fun maybeScheduleLockRetry(state: SessionState) {
        if (state != SessionState.LOCK_FAILED || lockRetries >= 1) return
        lockRetries = 1
        mainHandler.postDelayed({
            submit {
                diag.log("LOCK", "锁屏失败重试（第 1 次）")
                performLockNow()
            }
        }, LOCK_RETRY_MS)
    }

    private fun scheduleWatchdog(delayMs: Long) {
        cancelWatchdog()
        val token = Runnable { submit { process(SessionEvent.LockWatchdog(SystemClock.elapsedRealtime())) } }
        watchdogToken = token
        mainHandler.postDelayed(token, delayMs)
    }

    private fun cancelWatchdog() {
        watchdogToken?.let { mainHandler.removeCallbacks(it) }
        watchdogToken = null
    }

    /** 计时中的通知每秒刷新剩余时间（仅 UI 通知，不写磁盘） */
    private fun scheduleNotificationTick() {
        if (snapshot.isTiming) {
            if (tickToken == null) {
                val token = Runnable {
                    tickToken = null
                    if (snapshot.isTiming) {
                        notifier.update(snapshot)
                        scheduleNotificationTick()
                    }
                }
                tickToken = token
                mainHandler.postDelayed(token, TICK_MS)
            }
        } else {
            tickToken?.let { mainHandler.removeCallbacks(it) }
            tickToken = null
        }
    }

    private fun publish() {
        val snap = snapshot
        mainHandler.post {
            for (l in uiListeners) l(snap)
        }
    }

    // ------------------------------------------------------------------
    // 设备事实
    // ------------------------------------------------------------------

    private fun isInteractive(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /** 通话中的接近传感器黑屏不是会话结束信号（无需 READ_PHONE_STATE 的尽力判断） */
    private fun isInCall(): Boolean {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return false
        return try {
            am.mode == android.media.AudioManager.MODE_IN_CALL ||
                am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            false
        }
    }

    /** 持久化快照是否属于本次开机：boot_count 为主，单调时钟兜底 */
    private fun computeSameBoot(): Boolean {
        val savedBoot = repo.savedBootCount()
        val currentBoot = Diagnostics.bootCount(appContext)
        return if (savedBoot != -1 && currentBoot != -1) {
            savedBoot == currentBoot
        } else {
            repo.savedElapsed() <= SystemClock.elapsedRealtime()
        }
    }

    // ------------------------------------------------------------------
    // 服务启动 / 停止
    // ------------------------------------------------------------------

    fun startMonitoringService() {
        try {
            val intent = Intent(appContext, UnlockMonitorService::class.java)
                .putExtra(UnlockMonitorService.EXTRA_REASON, "controller 启动监控")
            appContext.startForegroundService(intent)
        } catch (e: Exception) {
            diag.log("SVC", "启动前台服务失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun stopMonitoringService() {
        try {
            appContext.stopService(Intent(appContext, UnlockMonitorService::class.java))
        } catch (e: Exception) {
            diag.log("SVC", "停止前台服务失败: ${e.message}")
        }
    }

    private fun clearedSnapshot() = snapshot.copy(state = SessionState.NO_SESSION)

    private fun label(ev: SessionEvent): String = when (ev) {
        is SessionEvent.ScreenOn -> "ScreenOn(interactive=${ev.interactive},kg=${ev.keyguardLocked})"
        is SessionEvent.ScreenOff -> "ScreenOff(secure=${ev.keyguardSecure},inCall=${ev.inCall})"
        is SessionEvent.UserPresent -> "UserPresent(kg=${ev.keyguardLocked})"
        is SessionEvent.AlarmFired -> "AlarmFired(id=${ev.firedSessionId},now=${ev.nowElapsed})"
        is SessionEvent.Selected ->
            "Selected(id=${ev.pendingSessionId},${if (ev.unlimited) "不限" else "${ev.durationMs}ms"})"
        is SessionEvent.LockOutcome -> "LockOutcome(ok=${ev.success},err=${ev.error})"
        is SessionEvent.LockWatchdog -> "LockWatchdog(now=${ev.nowElapsed})"
        is SessionEvent.Recover ->
            "Recover(interactive=${ev.interactive},kg=${ev.keyguardLocked},sameBoot=${ev.sameBoot})"
    }

    companion object {
        private const val REQUEST_CODE_ALARM = 1001
        private const val TICK_MS = 1000L
        private const val LOCK_RETRY_MS = 3000L
    }
}
