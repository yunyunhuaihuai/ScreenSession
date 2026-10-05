package com.local.unlocksession.logic

/**
 * 会话状态机使用的纯 Kotlin 模型：状态、事件、动作。
 * 不依赖 Android 类，保证状态流转可以在 JVM 上完整单测。
 *
 * 时间统一使用 SystemClock.elapsedRealtime() 的毫秒值（单调时钟），
 * 由事件携带 nowElapsed 传入，状态机内部不做任何系统调用。
 */

enum class SessionState {
    /** 已锁屏／无会话：没有活动计时，等待真正解锁 */
    NO_SESSION,

    /** 待选择：已解锁但尚未选择时长，期间由悬浮层阻断普通使用 */
    PENDING_SELECTION,

    /** 正在计时：截止时间已固定，只允许查看状态 */
    TIMING,

    /** 本次不限：不创建到期任务，提前提醒，锁屏后结束 */
    UNLIMITED,

    /** 已发出锁屏请求：lockNow() 已调用，等待系统熄屏确认 */
    LOCK_REQUESTED,

    /** 锁屏失败：管理员缺失或系统未确认熄屏，已记录明确错误 */
    LOCK_FAILED
}

/** 一次会话的持久化快照 */
data class SessionSnapshot(
    val state: SessionState = SessionState.NO_SESSION,
    /** 会话唯一标识；0 表示当前无会话 */
    val sessionId: Long = 0L,
    /** 计时开始（状态进入 TIMING/UNLIMITED）时刻的 elapsedRealtime，毫秒 */
    val startedElapsed: Long = 0L,
    /** 截止时刻的 elapsedRealtime，毫秒；0 表示无截止 */
    val deadlineElapsed: Long = 0L,
    /** 锁屏失败原因；仅 LOCK_FAILED 状态非空 */
    val lockError: String? = null,
    /** 到期闹钟是否成功排定；false 表示系统闹钟设置失败，依赖进程内兜底检查 */
    val alarmScheduled: Boolean = true,
    /** 本次会话的提前提醒配置快照（确认计时那一刻的设置，此后不受全局设置影响） */
    val sessionRemindersEnabled: Boolean = true,
    /** 本次会话的提前提醒时点快照（秒，已规范化：正数、去重、升序） */
    val sessionReminderThresholds: List<Long> = emptyList()
) {
    val isTiming: Boolean get() = state == SessionState.TIMING
    val isUnlimited: Boolean get() = state == SessionState.UNLIMITED
    val isActive: Boolean get() = isTiming || isUnlimited
    val wantsOverlay: Boolean get() = state == SessionState.PENDING_SELECTION
}

/** 状态机输入事件。事件必须携带处理时刻的设备事实，由调用方（SessionController）采集 */
sealed class SessionEvent {
    data class ScreenOn(
        val interactive: Boolean,
        val keyguardLocked: Boolean,
        /** 漏事件安全网可能新建会话时使用的标识 */
        val nextSessionId: Long
    ) : SessionEvent()

    /**
     * ACTION_SCREEN_OFF。
     * keyguardLocked/isDeviceLocked 用于区分“通话接近黑屏（keyguard 未接管）”与“真实锁屏”；
     * 到期锁屏确认（LOCK_REQUESTED 状态）不受 inCall 影响。
     */
    data class ScreenOff(
        val keyguardLocked: Boolean,
        val isDeviceLocked: Boolean,
        val keyguardSecure: Boolean,
        val inCall: Boolean
    ) : SessionEvent()

    /**
     * ACTION_USER_PRESENT（keyguard 真正消失）。
     * nextSessionId 由调用方从仓库预取：状态机需要新建会话时使用该值。
     * nowElapsed 用于判定“同解锁周期的重复/迟到事件”（R4）。
     */
    data class UserPresent(
        val keyguardLocked: Boolean,
        val nextSessionId: Long,
        val nowElapsed: Long
    ) : SessionEvent()

    /** 到期闹钟触发；firedSessionId 用于旧会话事件校验 */
    data class AlarmFired(val firedSessionId: Long, val nowElapsed: Long) : SessionEvent()

    /** 到期闹钟排定结果回传（R7：失败时如实展示，不允许假装计时可靠） */
    data class AlarmScheduleOutcome(val sessionId: Long, val ok: Boolean) : SessionEvent()

    /** 用户做出选择；pendingSessionId 必须与当前待选择会话一致 */
    data class Selected(
        val pendingSessionId: Long,
        val unlimited: Boolean,
        val durationMs: Long,
        val nowElapsed: Long
    ) : SessionEvent()

    /** lockNow() 调用结果回传；必须携带会话标识，旧会话结果不消费（R1） */
    data class LockOutcome(
        val sessionId: Long,
        val success: Boolean,
        val error: String?,
        val nowElapsed: Long
    ) : SessionEvent()

    /** 锁屏看门狗：发出锁屏请求后迟迟未见熄屏；携带会话标识（R1） */
    data class LockWatchdog(val sessionId: Long, val nowElapsed: Long) : SessionEvent()

    /**
     * 恢复检查（开机、服务重启、进程恢复、应用更新后）。
     * sameBoot 表示持久化快照是否与本机当前开机属于同一次开机；
     * canCreateSession 由控制器能力门（监控开/管理员/悬浮窗）决定，false 时不得新建待选择。
     */
    data class Recover(
        val interactive: Boolean,
        val keyguardLocked: Boolean,
        val sameBoot: Boolean,
        val canCreateSession: Boolean,
        val nowElapsed: Long,
        val nextSessionId: Long
    ) : SessionEvent()
}

/** 状态机要求的副作用，由 SessionController 串行执行 */
sealed class SessionAction {
    /** 持久化当前快照 */
    object Persist : SessionAction()

    object ShowSelectionOverlay : SessionAction()
    object HideSelectionOverlay : SessionAction()

    /** 设定到期精确闹钟（ELAPSED_REALTIME_WAKEUP） */
    data class ScheduleAlarm(val sessionId: Long, val deadlineElapsed: Long) : SessionAction()

    object CancelAlarm : SessionAction()

    /** 到期锁屏：调用 lockNow()，结果必须回传 LockOutcome */
    object RequestLockNow : SessionAction()

    /** 会话因提前熄屏结束时加强 keyguard，消除系统解锁宽限期 */
    object RequestLockOnScreenOff : SessionAction()

    object UpdateNotification : SessionAction()

    /** 能力未就绪（监控关闭/管理员缺失/悬浮窗缺失）时提醒 */
    object ShowNotReadyNotification : SessionAction()

    data class ScheduleLockWatchdog(val delayMs: Long) : SessionAction()
    object CancelLockWatchdog : SessionAction()
}

/** 一次状态流转的结果：新快照 + 副作用列表 + 可选备注（进入诊断日志） */
data class Transition(
    val snapshot: SessionSnapshot,
    val actions: List<SessionAction> = emptyList(),
    val note: String? = null
)

/** 会话时长输入的严格解析：拒绝空值、零、负数、非数字与超范围值 */
object DurationInput {
    const val MAX_MINUTES: Long = 2880L          // 48 小时
    const val MAX_SECONDS: Long = 7200L          // debug 秒级测试入口上限

    private val digitsOnly = Regex("^\\d+$")

    /** 解析分钟数，返回毫秒；非法输入返回 null（不把 0 当作不限） */
    fun parseMinutesToMs(raw: String?): Long? {
        val value = parseCount(raw) ?: return null
        if (value < 1 || value > MAX_MINUTES) return null
        val ms = value * 60_000L
        return if (ms <= 0) null else ms
    }

    /** debug 构建专用：按秒解析，返回毫秒 */
    fun parseSecondsToMs(raw: String?): Long? {
        val value = parseCount(raw) ?: return null
        if (value < 1 || value > MAX_SECONDS) return null
        return value * 1000L
    }

    private fun parseCount(raw: String?): Long? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!digitsOnly.matches(trimmed)) return null
        return trimmed.toLongOrNull() ?: return null
    }
}

/** 提前提醒时点输入：整数秒，范围沿用会话时长上限（48h），去重后升序 */
object ReminderInput {
    fun parseList(raw: String?): List<Long>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(',', '，', ' ')
        val out = ArrayList<Long>()
        for (p in parts) {
            val t = p.trim()
            if (t.isEmpty()) continue
            if (!digitsOnlySafe(t)) return null
            val v = t.toLongOrNull() ?: return null
            if (v < 1 || v > DurationInput.MAX_MINUTES * 60) return null
            if (v in out) return null // 重复值明确拒绝
            out.add(v)
        }
        if (out.isEmpty()) return null
        out.sort()
        return out
    }

    private fun digitsOnlySafe(t: String): Boolean = t.matches(Regex("^\\d+$"))
}
