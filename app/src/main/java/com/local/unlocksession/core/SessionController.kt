package com.local.unlocksession.core

import com.local.unlocksession.data.ReminderMark
import com.local.unlocksession.data.SessionStore
import com.local.unlocksession.session.SessionPanelBridge
import com.local.unlocksession.logic.ReminderPlanner
import com.local.unlocksession.logic.SessionAction
import com.local.unlocksession.logic.SessionEvent
import com.local.unlocksession.logic.SessionMachine
import com.local.unlocksession.logic.SessionSnapshot
import com.local.unlocksession.logic.SessionState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 会话控制器：把 UI、广播、服务恢复、闹钟事件统一汇入单线程串行处理，
 * 是状态机唯一的调用方和所有副作用的唯一执行方。
 *
 * 事件顺序：单线程执行器保证 FIFO；ScreenOff/UserPresent 等信号的设备事实
 * 在处理时刻通过 [ControllerEnv] 读取，避免广播陈旧值。
 *
 * 可靠性设计：
 * - R0：USER_PRESENT 到达但 keyguard 仍锁（密码解锁过渡期）→ 有限次数、可取消的短延迟复核；
 * - R1：锁屏请求/看门狗/重试全部携带会话标识并在执行前核对，会话结束即取消；
 * - R2：到期/提醒接收器经 goAsync 提交，冷启动先恢复核对开机归属与真实锁屏状态；
 * - R7：闹钟排定失败如实回传，进程内每秒兜底检查经同一状态机触发到期事件。
 */
class SessionController internal constructor(
    private val repo: SessionStore,
    private val diag: DiagSink,
    private val env: ControllerEnv,
    private val executor: ExecutorService
) {

    /** 生产入口：独立单线程执行器，保证事件 FIFO 串行 */
    constructor(repo: SessionStore, diag: DiagSink, env: ControllerEnv) : this(
        repo, diag, env, defaultExecutor()
    )

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
    private var lastTickTrace = 0L

    /** R0：keyguard 过渡期复核的代次，熄屏/新广播即作废旧复核 */
    private var recheckGeneration = 0L

    /** 测试提醒节流 */
    private var lastTestReminderAt = 0L

    // ---- 诊断健康信息（LogActivity 后台健康页读取；均为本进程可核实的事实） ----

    /** 最近一次恢复检查的原因 */
    @Volatile
    var lastRecoverReason: String? = null
        private set

    /** 最近一次监控服务启动原因 */
    @Volatile
    var lastServiceStartReason: String? = null
        private set

    /** 最近一次有效解锁检测（含心跳发现），人类可读时间 + 来源 */
    @Volatile
    var lastUnlockDetected: String = "（本次开机尚无记录）"
        private set

    private val uiListeners = CopyOnWriteArrayList<(SessionSnapshot) -> Unit>()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun startIfNeeded() {
        if (started) return
        started = true
        snapshot = repo.loadSnapshot()
        diag.log("INIT", "控制器启动 boot=${env.bootCount()} 恢复快照=$snapshot")
    }

    fun attachService(service: android.app.Service) {
        startIfNeeded()
        env.attachForegroundService(service)
        env.notifySession(snapshot, repo.isShowCountdown())
        // 服务挂载即协调后续监控任务（幂等）：亮屏且无会话时确保解锁心跳在跑，
        // 覆盖"服务在屏幕已亮、密码锁仍在、NO_SESSION 时被恢复"的场景
        coordinateUnlockHeartbeat("服务挂载")
    }

    fun detachService(service: android.app.Service) {
        env.detachForegroundService(service)
        diag.log("SVC", "前台服务 detached（系统可能稍后重启服务）")
    }

    fun addUiListener(listener: (SessionSnapshot) -> Unit) {
        uiListeners.add(listener)
        env.postUi { listener(snapshot) }
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
            interactive = env.isInteractive(),
            keyguardLocked = keyguardLockedFact(),
            nextSessionId = repo.peekNextSessionId()
        )
        if (gateIfNeeded(ev)) return@submit
        process(ev)
        // 亮屏期间启动一致性心跳：宽限期内无凭据放行不产生任何广播（实测 ColorOS 上滑
        // 放行连 USER_PRESENT 都不发），必须靠心跳发现"已解锁却无会话"的不一致状态
        if (env.isInteractive()) startUnlockHeartbeat()
    }

    fun signalScreenOff() = submit {
        startIfNeeded()
        // 真实锁屏周期开始：作废一切进行中的 keyguard 复核
        recheckGeneration++
        env.cancelDelayed(KEY_KG_RECHECK)
        env.cancelDelayed(KEY_HEARTBEAT)
        val ev = SessionEvent.ScreenOff(
            keyguardLocked = keyguardLockedFact(),
            isDeviceLocked = env.isDeviceLocked(),
            keyguardSecure = env.isKeyguardSecure(),
            inCall = env.isInCall()
        )
        process(ev)
    }

    fun signalUserPresent() = submit {
        startIfNeeded()
        val ev = SessionEvent.UserPresent(
            keyguardLocked = keyguardLockedFact(),
            nextSessionId = repo.peekNextSessionId(),
            nowElapsed = env.nowElapsed()
        )
        if (gateIfNeeded(ev)) return@submit
        if (ev.keyguardLocked) {
            // R0：密码解锁时 keyguard 消失动画/状态翻转可能慢于广播，
            // 做有限次数、可取消的事实复核；实际未锁且可交互后再创建待选择。
            startKeyguardRecheck()
            return@submit
        }
        noteUnlockDetected("USER_PRESENT")
        process(ev)
    }

    fun signalAlarm(firedSessionId: Long) = submit {
        startIfNeeded()
        if (!verifyColdStart("到期闹钟")) return@submit
        process(SessionEvent.AlarmFired(firedSessionId, env.nowElapsed()))
    }

    /** R2：接收器经 goAsync 提交；完成回调覆盖提交后的一切路径 */
    fun handleAlarmAsync(firedSessionId: Long, onFinished: () -> Unit) {
        try {
            executor.execute {
                try {
                    startIfNeeded()
                    if (verifyColdStart("到期闹钟")) {
                        process(SessionEvent.AlarmFired(firedSessionId, env.nowElapsed()))
                    }
                } catch (e: Exception) {
                    diag.log("ERR", "到期事件处理异常: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    // 广播拉起进程后恢复监控（幂等）。放在收尾阶段：到期锁屏由本广播
                    // 的受保护处理完成，绝不依赖服务启动成功；finish 前仍在闹钟广播
                    // 的临时允许名单窗口内，是恢复前台服务的合法时机。
                    try {
                        ensureMonitoringServiceForWake("到期闹钟")
                    } finally {
                        try {
                            onFinished()
                        } catch (e: Exception) {
                            diag.log("ERR", "广播 finish 异常: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 执行器已关闭等提交失败路径：也必须结束广播生命周期
            diag.log("ERR", "到期事件提交失败: ${e.message}")
            try {
                onFinished()
            } catch (_: Exception) {
            }
        }
    }

    /** 提醒闹钟到达：冷启动核对 → 迟到窗口判定 → 消费标记先行 → heads-up 投递 */
    fun handleReminderAsync(firedSessionId: Long, thresholdMs: Long, onFinished: () -> Unit) {
        try {
            executor.execute {
                try {
                    startIfNeeded()
                    if (!verifyColdStart("提前提醒")) return@execute
                    processReminder(firedSessionId, thresholdMs)
                } catch (e: Exception) {
                    diag.log("ERR", "提醒处理异常: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    try {
                        ensureMonitoringServiceForWake("提前提醒")
                    } finally {
                        try {
                            onFinished()
                        } catch (e: Exception) {
                            diag.log("ERR", "广播 finish 异常: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            diag.log("ERR", "提醒事件提交失败: ${e.message}")
            try {
                onFinished()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 系统唤醒入口（提醒/到期闹钟广播）把进程拉起后的幂等恢复：
     * 监控开启且服务未挂载时重新拉起前台服务（闹钟广播使应用进入临时允许名单，
     * Android 10 允许在该窗口启动前台服务；服务已在跑时重复调用只会多一次
     * onStartCommand，无副作用）。用户主动关闭监控后不得复活。
     * 启动失败只如实记录——本次事件的处理与到期锁屏不依赖服务启动成功。
     */
    private fun ensureMonitoringServiceForWake(source: String) {
        try {
            if (!repo.isMonitoringEnabled()) return
            if (env.isServiceAttached()) return
            diag.log("SVC", "$source 拉起进程且服务未挂载，恢复监控服务")
            env.startMonitoringService("$source 拉起进程恢复监控")
        } catch (e: Exception) {
            diag.log("SVC", "恢复监控服务失败（不影响本次事件处理）: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 恢复检查：开机、服务重启、进程恢复、应用更新后调用。
     * 服务可能在首次解锁广播之后才启动，必须主动核对当前状态补上选择界面。
     */
    fun recover(reason: String) = submit {
        startIfNeeded()
        lastRecoverReason = reason
        val interactive = env.isInteractive()
        val keyguardLocked = keyguardLockedFact()
        val sameBoot = computeSameBoot()
        diag.log(
            "RECOVER",
            "原因=$reason interactive=$interactive keyguardLocked=$keyguardLocked " +
                "sameBoot=$sameBoot (savedBoot=${repo.savedBootCount()} currentBoot=${env.bootCount()} " +
                "savedElapsed=${repo.savedElapsed()} nowElapsed=${env.nowElapsed()}) persisted=$snapshot"
        )
        val ev = SessionEvent.Recover(
            interactive = interactive,
            keyguardLocked = keyguardLocked,
            sameBoot = sameBoot,
            canCreateSession = capabilityGate() == null,
            nowElapsed = env.nowElapsed(),
            nextSessionId = repo.peekNextSessionId()
        )
        process(ev)
        // 恢复后协调后续监控任务（幂等）：亮屏且仍无会话（如屏幕已亮、密码锁仍在的
        // 恢复场景）时确保解锁心跳在跑，覆盖本机"解锁不发送 USER_PRESENT"的路径；
        // 已有会话/熄屏/能力缺失时该调用不做任何事，不会形成重复循环。
        coordinateUnlockHeartbeat("恢复($reason)")
    }

    /** 用户做出选择。输入非法返回 false（不产生事件）；选择从确认时刻开始计时 */
    fun select(pendingSessionId: Long, unlimited: Boolean, durationMs: Long?): Boolean {
        if (!unlimited && durationMs == null) return false
        startIfNeeded()
        submit {
            process(
                SessionEvent.Selected(
                    pendingSessionId, unlimited, durationMs ?: 0L, env.nowElapsed()
                )
            )
        }
        return true
    }

    // ------------------------------------------------------------------
    // 提醒
    // ------------------------------------------------------------------

    private fun processReminder(firedSessionId: Long, thresholdMs: Long) {
        val cur = snapshot
        if (cur.state != SessionState.TIMING || cur.sessionId != firedSessionId) {
            diag.log(
                "RMD",
                "提醒到达但会话不匹配（事件=$firedSessionId/${thresholdMs}ms 当前=${cur.sessionId}/${cur.state}），忽略旧事件"
            )
            return
        }
        val now = env.nowElapsed()
        if (now >= cur.deadlineElapsed) {
            // 已到期：优先执行到期处理，不再发提前提醒
            diag.log("RMD", "提醒到达时已到期（now=$now ≥ deadline=${cur.deadlineElapsed}），转到期处理")
            process(SessionEvent.AlarmFired(firedSessionId, now))
            return
        }
        val planned = ReminderPlanner.PlannedReminder(thresholdMs, cur.deadlineElapsed - thresholdMs)
        return when (ReminderPlanner.classify(planned, now)) {
            ReminderPlanner.Outcome.SCHEDULE_FUTURE -> {
                // 闹钟异常提前：按计划时间重排，不投递
                env.scheduleReminderAlarm(firedSessionId, thresholdMs, planned.triggerElapsed)
                diag.log("RMD", "提醒异常提前（剩余 ${planned.triggerElapsed - now}ms），已重排")
            }

            ReminderPlanner.Outcome.SKIP_MISSED -> {
                // 历史错过：只记录，不补发（消费标记一并落盘防重复）
                repo.markReminderConsumed("$firedSessionId:$thresholdMs")
                diag.log("RMD", "提醒已错过超过 ${ReminderPlanner.LATE_WINDOW_MS}ms 窗口，跳过不补发")
            }

            ReminderPlanner.Outcome.DELIVER_NOW -> {
                // 消费标记在通知请求之前同步落盘（commit）。通知请求与磁盘事务无法组成
                // 原子操作：崩溃窗口为"标记已存、通知未发"——本产品优先避免重复历史
                // 提醒，不能承诺用户一定看到了每条提醒。
                when (repo.markReminderConsumed("$firedSessionId:$thresholdMs")) {
                    ReminderMark.NEW -> {
                        if (!env.notificationsEnabled()) {
                            diag.log("RMD", "提醒投递时通知已被系统禁用，本次提醒无法呈现")
                            return
                        }
                        env.notifyReminder(firedSessionId, thresholdMs)
                        diag.log(
                            "RMD",
                            "提前提醒已投递 threshold=${thresholdMs}ms now=$now trigger=${planned.triggerElapsed} " +
                                "（实际迟到 ${now - planned.triggerElapsed}ms）"
                        )
                    }

                    ReminderMark.DUPLICATE ->
                        diag.log("RMD", "提醒重复投递被抑制（标记已存在）")

                    ReminderMark.WRITE_FAILED ->
                        // 写入失败≠去重成功：持久化状态未知，保守跳过投递避免重复提醒
                        diag.log("RMD", "消费标记写盘失败，保守跳过本次投递（避免重复提醒）threshold=${thresholdMs}ms")
                }
            }
        }
    }

    /** 会话计时确认/恢复后，按快照重新安排提醒任务 */
    private fun armReminders(snap: SessionSnapshot, nowElapsed: Long, recovering: Boolean) {
        if (!snap.isTiming) return
        val duration = snap.deadlineElapsed - snap.startedElapsed
        val planned = ReminderPlanner.plan(snap.sessionReminderThresholds, snap.deadlineElapsed, duration)
        val skippedKeys = ArrayList<String>()
        for (p in planned) {
            val key = "${snap.sessionId}:${p.thresholdMs}"
            if (key in repo.consumedReminders()) continue
            when {
                !recovering -> {
                    val ok = env.scheduleReminderAlarm(snap.sessionId, p.thresholdMs, p.triggerElapsed)
                    diag.log("RMD", "提醒已排定 threshold=${p.thresholdMs}ms trigger=${p.triggerElapsed} ok=$ok")
                }

                nowElapsed < p.triggerElapsed -> {
                    val ok = env.scheduleReminderAlarm(snap.sessionId, p.thresholdMs, p.triggerElapsed)
                    diag.log("RMD", "恢复：未来提醒重排 threshold=${p.thresholdMs}ms ok=$ok")
                }

                nowElapsed <= p.triggerElapsed + ReminderPlanner.LATE_WINDOW_MS -> {
                    // 恢复时仍在投递迟到窗口内：照常提醒一次
                    when (repo.markReminderConsumed(key)) {
                        ReminderMark.NEW -> {
                            if (env.notificationsEnabled()) {
                                env.notifyReminder(snap.sessionId, p.thresholdMs)
                                diag.log("RMD", "恢复：提醒在迟到窗口内补投 threshold=${p.thresholdMs}ms")
                            } else {
                                diag.log("RMD", "恢复：窗口内提醒但通知被禁用，无法呈现")
                            }
                        }

                        ReminderMark.DUPLICATE -> diag.log("RMD", "恢复：窗口内提醒已被消费过，跳过 key=$key")

                        ReminderMark.WRITE_FAILED ->
                            diag.log("RMD", "恢复：消费标记写盘失败，保守跳过投递 key=$key")
                    }
                }

                else -> {
                    skippedKeys.add(key)
                }
            }
        }
        if (skippedKeys.isNotEmpty()) {
            if (!repo.markRemindersSkipped(skippedKeys)) {
                diag.log("ERR", "跳过标记写盘失败 keys=$skippedKeys")
            }
            diag.log("RMD", "恢复：${skippedKeys.size} 个历史提醒已错过，跳过不补发")
        }
    }

    /** 测试提醒：使用真实 channel 与构建路径，不改状态/截止/消费标记/锁屏 */
    fun testReminder(): Boolean {
        startIfNeeded()
        if (!env.notificationsEnabled()) return false
        val now = env.nowElapsed()
        if (now - lastTestReminderAt < TEST_REMINDER_THROTTLE_MS) return false
        lastTestReminderAt = now
        submit {
            env.notifyTestReminder()
            diag.log("RMD", "测试提醒已发出（不占用真实提醒标记，不锁屏）")
            env.postDelayed(KEY_TEST_REMINDER_CLEAR, TEST_REMINDER_AUTO_CLEAR_MS) {
                env.cancelTestReminder()
            }
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
            env.notifySession(clearedSnapshot(), repo.isShowCountdown())
            stopMonitoringService()
        }
        return true
    }

    fun quickMinutes(): List<Int> = repo.getQuickMinutes()

    fun quickLabels(): List<String> = repo.getQuickMinutes().map { "${it} 分钟" }

    /** UI 读取监控开关（不做会话判断） */
    fun isMonitoringEnabledForUi(): Boolean = repo.isMonitoringEnabled()

    /** 保存三个快捷时长（分钟）。只影响之后的新会话，不改变已启动的截止时间 */
    fun setQuickMinutesPublic(values: List<Int>): Boolean {
        val ok = repo.setQuickMinutes(values)
        if (ok) diag.log("CFG", "快捷时长已更新：$values（当前会话不受影响）")
        return ok
    }

    fun isShowCountdown(): Boolean = repo.isShowCountdown()

    /** 只控制显示，立即生效：不改截止时间、不停服务、不取消闹钟 */
    fun setShowCountdown(value: Boolean) {
        repo.setShowCountdown(value)
        diag.log("CFG", "通知栏倒计时显示 → $value（仅显示设置）")
        submit { env.notifySession(snapshot, value) }
    }

    fun isRemindersEnabled(): Boolean = repo.isRemindersEnabled()

    fun setRemindersEnabled(value: Boolean) {
        repo.setRemindersEnabled(value)
        diag.log("CFG", "提前提醒开关 → $value（下次会话生效）")
    }

    fun reminderThresholds(): List<Long> = repo.getReminderThresholds()

    /** 保存时点（秒，规范化）；只影响之后的新会话 */
    fun setReminderThresholds(values: List<Long>): Boolean {
        val norm = ReminderPlanner.normalize(values)
        val ok = repo.setReminderThresholds(norm)
        if (ok) diag.log("CFG", "提前提醒时点已更新：$norm 秒（下次会话生效）")
        return ok
    }

    fun isDebugSecondsMode(): Boolean = repo.isDebugSecondsMode()

    /** UI 读取系统通知总开关（用于测试提醒按钮的禁用提示） */
    fun notificationsEnabledForUi(): Boolean {
        startIfNeeded()
        return env.notificationsEnabled()
    }

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

    internal fun panelBridge(): SessionPanelBridge = panelBridge

    internal fun submitSelection(pendingSessionId: Long, unlimited: Boolean, durationMs: Long?) =
        select(pendingSessionId, unlimited, durationMs)

    // ------------------------------------------------------------------
    // R0：keyguard 过渡期复核
    // ------------------------------------------------------------------

    private fun startKeyguardRecheck() {
        recheckGeneration++
        val gen = recheckGeneration
        var remaining = KG_RECHECK_MAX_TRIES
        diag.log("RCK", "USER_PRESENT 到达但 keyguard 仍锁定（密码解锁过渡期？），开始有限复核 gen=$gen")
        fun recheck() {
            submit {
                if (gen != recheckGeneration) return@submit
                val locked = keyguardLockedFact()
                if (!locked && env.isInteractive()) {
                    noteUnlockDetected("USER_PRESENT 复核")
                    diag.log("RCK", "复核通过 gen=$gen：keyguard 已消失，视为有效解锁")
                    process(SessionEvent.UserPresent(false, repo.peekNextSessionId(), env.nowElapsed()))
                } else {
                    remaining--
                    if (remaining <= 0) {
                        diag.log("RCK", "复核放弃 gen=$gen：${KG_RECHECK_MAX_TRIES} 次内 keyguard 始终锁定")
                    } else {
                        env.postDelayed(KEY_KG_RECHECK, KG_RECHECK_INTERVAL_MS) { recheck() }
                    }
                }
            }
        }
        env.postDelayed(KEY_KG_RECHECK, KG_RECHECK_INTERVAL_MS) { recheck() }
    }

    private fun keyguardLockedFact(): Boolean = env.isKeyguardLocked() || env.isDeviceLocked()

    // ------------------------------------------------------------------
    // R0 补充：解锁一致性心跳
    // ------------------------------------------------------------------

    /**
     * 亮屏期间每 2 秒核对"可交互 + keyguard 未锁 + 无会话"。
     * 仅在亮屏时运行、熄屏即停、发现不一致（补出待选择）即停；
     * 不写磁盘、不持有唤醒锁，不替代 USER_PRESENT 主路径，
     * 专堵"宽限期无凭据放行""解锁不发送 USER_PRESENT""服务亮屏恢复后解锁"等
     * 不产生可用广播的解锁路径。
     */
    private fun startUnlockHeartbeat() {
        env.postDelayed(KEY_HEARTBEAT, HEARTBEAT_MS) { submit { heartbeatOnce() } }
    }

    /** 恢复协调入口（幂等）：亮屏且 NO_SESSION 时确保解锁心跳在轮询 */
    private fun coordinateUnlockHeartbeat(source: String) {
        if (!env.isInteractive()) return
        if (snapshot.state != SessionState.NO_SESSION) return
        diag.log("HB", "$source：亮屏且无会话，启动解锁心跳轮询")
        startUnlockHeartbeat()
    }

    /** 记录最近一次有效解锁检测（诊断健康页展示） */
    private fun noteUnlockDetected(source: String) {
        val wall = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        lastUnlockDetected = "$wall（$source）"
    }

    private fun heartbeatOnce() {
        if (!env.isInteractive()) {
            diag.log("HB", "心跳停止：已熄屏")
            return
        }
        if (snapshot.state != SessionState.NO_SESSION) return // 会话存在：无需再查
        if (!repo.isMonitoringEnabled() || !env.isAdminActive() || !env.canDrawOverlays()) return
        if (!env.isKeyguardLocked() && !env.isDeviceLocked()) {
            noteUnlockDetected("解锁心跳")
            diag.log("HB", "心跳发现已解锁且无会话：补上待选择（宽限期放行或广播丢失）")
            process(SessionEvent.ScreenOn(interactive = true, keyguardLocked = false, nextSessionId = repo.peekNextSessionId()))
            return
        }
        env.postDelayed(KEY_HEARTBEAT, HEARTBEAT_MS) { submit { heartbeatOnce() } }
    }

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
                (label(ev) + " → 无变化 state=${from.state}(${from.sessionId})")
                    .let { if (t.note != null) "$it note=${t.note}" else it }
            )
            return
        }
        var newSnap = t.snapshot
        // 会话提醒配置快照：确认计时那一刻的全局设置，此后本次会话不受设置修改影响
        if (newSnap.state == SessionState.TIMING && from.state == SessionState.PENDING_SELECTION) {
            val cfgEnabled = repo.isRemindersEnabled()
            val cfgThresholds = if (cfgEnabled) repo.getReminderThresholds() else emptyList()
            newSnap = newSnap.copy(
                sessionRemindersEnabled = cfgEnabled,
                sessionReminderThresholds = ReminderPlanner.normalize(cfgThresholds)
            )
        }
        snapshot = newSnap
        if (newSnap.sessionId != from.sessionId) {
            if (from.sessionId != 0L) previousSessionId = from.sessionId
            // 会话标识先于快照同步落盘：即使快照写盘失败，恢复后也不会复用旧标识
            if (newSnap.sessionId != 0L && !repo.commitSessionId(newSnap.sessionId)) {
                diag.log("ERR", "会话标识写盘失败 id=${newSnap.sessionId}（重启后可能重号）")
            }
        }
        // R1：会话结束/替换时取消锁屏重试与看门狗
        if (newSnap.sessionId != from.sessionId ||
            (from.state in LOCK_STATES && newSnap.state !in LOCK_STATES)
        ) {
            cancelLockTasks(from.sessionId)
        }
        diag.log(
            "EVT",
            (label(ev) + ": ${from.state}(${from.sessionId}) → ${newSnap.state}(${newSnap.sessionId})")
                .let { if (t.note != null) "$it note=${t.note}" else it }
        )
        // 会话结束：清理该会话的提醒任务与通知（用旧会话自己的快照配置）。
        // 单位边界：快照存"秒"，取消接口收"毫秒"——必须换算后才能与排定时的
        // 任务身份（requestCode=200000+秒、通知 ID=5000+秒）对上，否则取消落空。
        if (from.isActive && !newSnap.isActive) {
            val thresholdsMs = from.sessionReminderThresholds.map { it * 1000L }
            env.cancelReminderAlarms(thresholdsMs)
            env.cancelReminderNotifications(thresholdsMs)
            if (!repo.clearConsumedReminders(from.sessionId)) {
                diag.log("ERR", "会话 ${from.sessionId} 消费标记清理写盘失败（残留标记不影响新会话）")
            }
        }
        execute(t.actions, newSnap, from)
        publish()
        scheduleNotificationTick()
        maybeScheduleLockRetry(newSnap)
        // 计时确认后安排提醒（使用确认时刻的快照配置）
        if (newSnap.isTiming && from.state == SessionState.PENDING_SELECTION) {
            armReminders(newSnap, env.nowElapsed(), recovering = false)
        }
        // 恢复续期后按当前时刻重排/跳过提醒（错过的不补发）
        if (newSnap.isTiming && ev is SessionEvent.Recover) {
            armReminders(newSnap, env.nowElapsed(), recovering = true)
        }
    }

    private fun execute(actions: List<SessionAction>, snap: SessionSnapshot, from: SessionSnapshot) {
        for (a in actions) {
            try {
                when (a) {
                    SessionAction.Persist -> {
                        repo.setBootAtSave(env.bootCount())
                        if (!repo.saveSnapshot(snap)) {
                            diag.log(
                                "ERR",
                                "快照写盘失败 state=${snap.state} id=${snap.sessionId}（进程回收后可能回退旧状态）"
                            )
                        }
                    }

                    SessionAction.ShowSelectionOverlay -> showSelectionOverlay(snap)
                    SessionAction.HideSelectionOverlay -> env.hideSelectionOverlay()

                    is SessionAction.ScheduleAlarm -> scheduleDeadlineAlarm(a.sessionId, a.deadlineElapsed)
                    SessionAction.CancelAlarm -> env.cancelDeadlineAlarm()

                    SessionAction.RequestLockNow -> performLockNow(snap.sessionId, SessionState.LOCK_REQUESTED)
                    SessionAction.RequestLockOnScreenOff -> env.enforceLockOnScreenOff()

                    SessionAction.UpdateNotification -> env.notifySession(snap, repo.isShowCountdown())
                    SessionAction.ShowNotReadyNotification -> env.notifyNotReady("功能未就绪")

                    is SessionAction.ScheduleLockWatchdog -> scheduleWatchdog(a.delayMs, snap.sessionId)
                    SessionAction.CancelLockWatchdog -> env.cancelDelayed(KEY_WATCHDOG)
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
            env.cancelNotReady()
            return false
        }
        diag.log("GATE", "事件 ${label(ev)} 被拒：$missing")
        env.notifyNotReady(missing)
        return true
    }

    private fun capabilityGate(): String? {
        if (!repo.isMonitoringEnabled()) return "监控已关闭"
        if (!env.isAdminActive()) return "设备管理员未启用"
        if (!env.canDrawOverlays()) return "悬浮窗权限未授予"
        return null
    }

    private fun showSelectionOverlay(snap: SessionSnapshot) {
        if (!env.canDrawOverlays()) {
            diag.log("OVF", "无法显示选择层：悬浮窗权限缺失")
            env.notifyNotReady("悬浮窗权限未授予")
            return
        }
        env.postUi {
            try {
                env.showSelectionOverlay(snap.copy())
            } catch (e: Exception) {
                diag.log("OVF", "显示选择层异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun scheduleDeadlineAlarm(sessionId: Long, deadlineElapsed: Long) {
        val ok = try {
            env.scheduleDeadlineAlarm(sessionId, deadlineElapsed)
        } catch (e: Exception) {
            diag.log("ALARM", "设定闹钟异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        diag.log(
            "ALARM",
            "到期闹钟排定 sessionId=$sessionId deadline=$deadlineElapsed " +
                "剩余=${deadlineElapsed - env.nowElapsed()}ms ok=$ok"
        )
        // R7：排定结果回传状态机；失败时快照如实标记，进程内兜底检查接管
        submit { process(SessionEvent.AlarmScheduleOutcome(sessionId, ok)) }
    }

    /** 到期锁屏：先校验会话，再调用 lockNow()，结果回传状态机（R1） */
    private fun performLockNow(sessionId: Long, expectState: SessionState) {
        val cur = snapshot
        if (cur.state != expectState || cur.sessionId != sessionId) {
            diag.log(
                "LOCK",
                "锁屏请求取消执行：会话不匹配（请求=$sessionId/$expectState 当前=${cur.sessionId}/${cur.state}）"
            )
            return
        }
        val (ok, err) = env.lockNow()
        submit {
            process(SessionEvent.LockOutcome(sessionId, ok, err, env.nowElapsed()))
        }
    }

    /** 提前熄屏结束会话时加强 keyguard（env 内部判断管理员+安全凭据） */
    private fun scheduleWatchdog(delayMs: Long, sessionId: Long) {
        env.cancelDelayed(KEY_WATCHDOG)
        env.postDelayed(KEY_WATCHDOG, delayMs) {
            submit {
                process(SessionEvent.LockWatchdog(sessionId, env.nowElapsed()))
            }
        }
    }

    /** 锁屏失败后自动重试一次（3 秒后），仍失败则保持失败状态并记录 */
    private fun maybeScheduleLockRetry(snap: SessionSnapshot) {
        if (snap.state != SessionState.LOCK_FAILED || lockRetries >= 1) return
        lockRetries = 1
        val sessionId = snap.sessionId
        env.postDelayed(KEY_LOCK_RETRY, LOCK_RETRY_MS) {
            submit {
                val cur = snapshot
                if (cur.state != SessionState.LOCK_FAILED || cur.sessionId != sessionId) {
                    diag.log("LOCK", "锁屏重试取消执行：会话已变化（原=$sessionId 当前=${cur.sessionId}/${cur.state}）")
                    return@submit
                }
                diag.log("LOCK", "锁屏失败重试（第 1 次）")
                performLockNow(sessionId, SessionState.LOCK_FAILED)
            }
        }
    }

    private fun cancelLockTasks(sessionId: Long) {
        env.cancelDelayed(KEY_WATCHDOG)
        env.cancelDelayed(KEY_LOCK_RETRY)
        diag.log("LOCK", "已取消会话 $sessionId 的看门狗与重试任务")
    }

    /** 计时中的通知刷新 + 进程内到期兜底检查（R7；兜底经同一状态机、原截止时间与会话标识） */
    private fun scheduleNotificationTick() {
        if (!snapshot.isTiming) {
            env.cancelDelayed(KEY_TICK)
            return
        }
        if (env.hasDelayed(KEY_TICK)) return
        env.postDelayed(KEY_TICK, TICK_MS) { submit { tickOnce() } }
    }

    private fun tickOnce() {
        val cur = snapshot
        if (!cur.isTiming) return
        val now = env.nowElapsed()
        // 低频轨迹：验证 tick 链连续性（每 10s 一行，不做每秒刷屏）
        if (now - lastTickTrace >= 10_000L) {
            lastTickTrace = now
            diag.log("TICK-TRACE", "tick 运行中 now=$now deadline=${cur.deadlineElapsed}")
        }
        if (now >= cur.deadlineElapsed) {
            // 兜底到期：与系统闹钟走同一状态机路径，幂等
            diag.log("TICK", "进程内兜底检查触发到期（now=$now ≥ deadline=${cur.deadlineElapsed}）")
            process(SessionEvent.AlarmFired(cur.sessionId, now))
            return
        }
        deliverDueRemindersViaTick(cur, now)
        if (repo.isShowCountdown()) env.notifySession(cur, true)
        env.postDelayed(KEY_TICK, TICK_MS) { submit { tickOnce() } }
    }

    /**
     * 提醒兜底：真机（ColorOS）实测会把同应用的多个精确闹钟合并推迟（60s 提醒被推到
     * 30s 时刻才触发，超窗被正确跳过）。tick 每秒运行，发现"已到点且仍在迟到窗口内"
     * 的提醒立即投递（消费标记先行），把提醒准时性从闹钟路径的 ±OEM 推迟补到 ±1s。
     * 与广播/恢复路径共用 [ReminderPlanner.classify] 的 2 秒迟到窗口与消费判定：
     * 超窗（回调停顿跨过窗口）记录为跳过，绝不补发过时的"一分钟/半分钟"提醒。
     * 到期锁屏不依赖本兜底（主路径仍是到期闹钟，且 tickOnce 先检查截止）。
     */
    private fun deliverDueRemindersViaTick(snap: SessionSnapshot, now: Long) {
        if (snap.sessionReminderThresholds.isEmpty()) return
        val duration = snap.deadlineElapsed - snap.startedElapsed
        val skipped = ArrayList<String>(1)
        for (t in snap.sessionReminderThresholds) {
            val thresholdMs = t * 1000L
            if (thresholdMs !in 1 until duration) continue
            val trigger = snap.deadlineElapsed - thresholdMs
            if (now < trigger) continue
            val key = "${snap.sessionId}:$thresholdMs"
            if (key in repo.consumedReminders()) continue
            when (ReminderPlanner.classify(ReminderPlanner.PlannedReminder(thresholdMs, trigger), now)) {
                // 不可达：now < trigger 已在上方过滤（classify 仅在 now<trigger 时返回 FUTURE）
                ReminderPlanner.Outcome.SCHEDULE_FUTURE -> Unit

                ReminderPlanner.Outcome.SKIP_MISSED -> {
                    skipped.add(key)
                    diag.log("RMD", "tick 发现提醒已超迟到窗口（now=$now trigger=$trigger），跳过不补发")
                }

                ReminderPlanner.Outcome.DELIVER_NOW -> when (repo.markReminderConsumed(key)) {
                    ReminderMark.NEW -> {
                        if (env.notificationsEnabled()) {
                            env.notifyReminder(snap.sessionId, thresholdMs)
                            diag.log("RMD", "tick 兜底投递提醒 threshold=${thresholdMs}ms now=$now trigger=$trigger")
                        } else {
                            diag.log("RMD", "tick 兜底发现到点提醒但通知被禁用，无法呈现 threshold=${thresholdMs}ms")
                        }
                    }

                    ReminderMark.DUPLICATE -> Unit // 广播路径已投递：去重生效

                    ReminderMark.WRITE_FAILED ->
                        diag.log("RMD", "tick：消费标记写盘失败，保守跳过投递 key=$key")
                }
            }
        }
        if (skipped.isNotEmpty() && !repo.markRemindersSkipped(skipped)) {
            diag.log("ERR", "tick 跳过标记写盘失败 keys=$skipped")
        }
    }

    /** 冷启动核对：开机归属 + 真实锁屏状态（R2）。返回 false 表示事件应丢弃 */
    private fun verifyColdStart(source: String): Boolean {
        val sameBoot = computeSameBoot()
        val interactive = env.isInteractive()
        val keyguardLocked = keyguardLockedFact()
        diag.log(
            "COLD",
            "$source 冷启动核对: sameBoot=$sameBoot interactive=$interactive keyguardLocked=$keyguardLocked " +
                "persisted=(${snapshot.state}/${snapshot.sessionId})"
        )
        if (!sameBoot) {
            process(
                SessionEvent.Recover(
                    interactive, keyguardLocked, sameBoot = false, canCreateSession = capabilityGate() == null,
                    nowElapsed = env.nowElapsed(), nextSessionId = repo.peekNextSessionId()
                )
            )
            return false
        }
        if (!interactive || keyguardLocked) {
            // 已锁屏/熄屏：旧快照不可信（漏收熄屏事件），先恢复核对，会话按事实结束
            process(
                SessionEvent.Recover(
                    interactive, keyguardLocked, sameBoot = true, canCreateSession = capabilityGate() == null,
                    nowElapsed = env.nowElapsed(), nextSessionId = repo.peekNextSessionId()
                )
            )
            return false
        }
        return true
    }

    private fun publish() {
        val snap = snapshot
        env.postUi {
            for (l in uiListeners) l(snap)
        }
    }

    // ------------------------------------------------------------------
    // 设备事实（委托 env）
    // ------------------------------------------------------------------

    /** 持久化快照是否属于本次开机：boot_count 为主，单调时钟兜底 */
    private fun computeSameBoot(): Boolean {
        val savedBoot = repo.savedBootCount()
        val currentBoot = env.bootCount()
        return if (savedBoot != -1 && currentBoot != -1) {
            savedBoot == currentBoot
        } else {
            repo.savedElapsed() <= env.nowElapsed()
        }
    }

    // ------------------------------------------------------------------
    // 服务启动 / 停止
    // ------------------------------------------------------------------

    fun startMonitoringService() {
        lastServiceStartReason = "controller 启动监控"
        env.startMonitoringService("controller 启动监控")
    }

    /** 诊断健康页：监控前台服务当前是否挂载 */
    fun isServiceAttached(): Boolean {
        startIfNeeded()
        return env.isServiceAttached()
    }

    private fun stopMonitoringService() {
        env.stopMonitoringService()
    }

    private fun clearedSnapshot() = snapshot.copy(state = SessionState.NO_SESSION)

    private fun label(ev: SessionEvent): String = when (ev) {
        is SessionEvent.ScreenOn -> "ScreenOn(interactive=${ev.interactive},kg=${ev.keyguardLocked})"
        is SessionEvent.ScreenOff ->
            "ScreenOff(kg=${ev.keyguardLocked},devLocked=${ev.isDeviceLocked},secure=${ev.keyguardSecure},inCall=${ev.inCall})"
        is SessionEvent.UserPresent -> "UserPresent(kg=${ev.keyguardLocked},now=${ev.nowElapsed})"
        is SessionEvent.AlarmFired -> "AlarmFired(id=${ev.firedSessionId},now=${ev.nowElapsed})"
        is SessionEvent.AlarmScheduleOutcome -> "AlarmScheduleOutcome(id=${ev.sessionId},ok=${ev.ok})"
        is SessionEvent.Selected ->
            "Selected(id=${ev.pendingSessionId},${if (ev.unlimited) "不限" else "${ev.durationMs}ms"})"
        is SessionEvent.LockOutcome -> "LockOutcome(id=${ev.sessionId},ok=${ev.success},err=${ev.error})"
        is SessionEvent.LockWatchdog -> "LockWatchdog(id=${ev.sessionId},now=${ev.nowElapsed})"
        is SessionEvent.Recover ->
            "Recover(interactive=${ev.interactive},kg=${ev.keyguardLocked},sameBoot=${ev.sameBoot},canCreate=${ev.canCreateSession})"
    }

    companion object {
        private fun defaultExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "session-controller").apply { isDaemon = false }
            }

        private const val KEY_TICK = "tick"
        private const val KEY_WATCHDOG = "watchdog"
        private const val KEY_LOCK_RETRY = "lock-retry"
        private const val KEY_KG_RECHECK = "kg-recheck"
        private const val KEY_HEARTBEAT = "unlock-heartbeat"
        private const val KEY_TEST_REMINDER_CLEAR = "test-reminder-clear"

        private const val TICK_MS = 1000L
        private const val LOCK_RETRY_MS = 3000L
        private const val KG_RECHECK_INTERVAL_MS = 300L
        private const val KG_RECHECK_MAX_TRIES = 6
        private const val HEARTBEAT_MS = 2000L
        private const val TEST_REMINDER_THROTTLE_MS = 2000L
        private const val TEST_REMINDER_AUTO_CLEAR_MS = 10_000L

        private val LOCK_STATES = setOf(SessionState.LOCK_REQUESTED, SessionState.LOCK_FAILED)
    }
}
