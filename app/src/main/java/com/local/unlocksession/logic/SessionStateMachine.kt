package com.local.unlocksession.logic

/**
 * 解锁限时会话状态机。
 *
 * 设计约束（对应交付说明中的会话规则）：
 * - 所有 UI、广播、服务恢复、闹钟事件都走 reduce()，由 SessionController 在单线程上串行调用；
 * - 任何到期/锁屏事件必须核对会话标识，旧会话事件一律忽略；
 * - 截止时间一旦设定不再改变（不接受取消/暂停/延长/缩短/转不限）；
 * - 熄屏即结束会话（通话中接近传感器熄屏除外）；
 * - 输入是纯函数：同样输入得到同样输出，便于单测幂等与恢复。
 */
object SessionMachine {

    /** 闹钟允许的提前触发容差（毫秒） */
    const val ALARM_TOLERANCE_MS: Long = 1500L

    /** 锁屏看门狗默认延时：lockNow 之后仍未确认熄屏则判失败 */
    const val LOCK_WATCHDOG_MS: Long = 10_000L

    /** 会话时长上限（毫秒）：48 小时，防溢出与误输入 */
    const val MAX_SESSION_MS: Long = DurationInput.MAX_MINUTES * 60_000L

    fun reduce(cur: SessionSnapshot, ev: SessionEvent): Transition = when (ev) {
        is SessionEvent.ScreenOn -> onScreenOn(cur, ev)
        is SessionEvent.ScreenOff -> onScreenOff(cur, ev)
        is SessionEvent.UserPresent -> onUserPresent(cur, ev)
        is SessionEvent.AlarmFired -> onAlarmFired(cur, ev)
        is SessionEvent.Selected -> onSelected(cur, ev)
        is SessionEvent.LockOutcome -> onLockOutcome(cur, ev)
        is SessionEvent.LockWatchdog -> onLockWatchdog(cur, ev)
        is SessionEvent.Recover -> onRecover(cur, ev)
    }

    // ------------------------------------------------------------------
    // SCREEN_ON：只亮屏不是解锁，不允许凭它创建会话
    // ------------------------------------------------------------------
    private fun onScreenOn(cur: SessionSnapshot, ev: SessionEvent.ScreenOn): Transition {
        // 安全网：亮屏且未锁且无会话——说明发生了漏记的熄屏/解锁（宽限期、进程死亡等）。
        // 此时普通应用已经可用，必须立即进入待选择，否则会绕过选择要求。
        if (cur.state == SessionState.NO_SESSION && ev.interactive && !ev.keyguardLocked) {
            return Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "screen_on 时已交互未锁屏且无会话：漏事件安全网，进入待选择"
            )
        }
        // 计时中亮屏但 keyguard 处于锁定：说明期间发生过一次我们漏记的真实锁屏，
        // 会话应当已经结束——现在补记结束，等待 USER_PRESENT 开新会话。
        if (cur.state == SessionState.TIMING && ev.keyguardLocked) {
            return Transition(
                cleared(),
                listOf(
                    SessionAction.CancelAlarm,
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification
                ),
                "计时中亮屏且 keyguard 已锁：补记漏掉的熄屏结束"
            )
        }
        return Transition(cur)
    }

    // ------------------------------------------------------------------
    // SCREEN_OFF：熄屏即结束会话（通话中熄屏除外）
    // ------------------------------------------------------------------
    private fun onScreenOff(cur: SessionSnapshot, ev: SessionEvent.ScreenOff): Transition {
        if (ev.inCall) {
            // 通话中的接近传感器黑屏不是会话结束信号
            return Transition(cur, note = "通话中熄屏，忽略（state=${cur.state}）")
        }
        return when (cur.state) {
            SessionState.NO_SESSION -> Transition(cur)

            SessionState.PENDING_SELECTION -> Transition(
                cleared(),
                listOf(
                    SessionAction.HideSelectionOverlay,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification
                ),
                "待选择中熄屏：放弃本次选择"
            )

            SessionState.TIMING -> Transition(
                cleared(),
                listOf(
                    SessionAction.CancelAlarm,
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification,
                    // 消除系统解锁宽限期：若设置了安全锁屏，则熄屏同时立即锁定
                    SessionAction.RequestLockOnScreenOff
                ),
                "计时中熄屏：会话结束"
            )

            SessionState.UNLIMITED -> Transition(
                cleared(),
                listOf(
                    SessionAction.CancelAlarm,
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification,
                    SessionAction.RequestLockOnScreenOff
                ),
                "本次不限中熄屏：会话结束"
            )

            SessionState.LOCK_REQUESTED -> Transition(
                cleared(),
                listOf(
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification
                ),
                "锁屏请求后确认熄屏：到期锁屏完成"
            )

            SessionState.LOCK_FAILED -> Transition(
                cleared(),
                listOf(SessionAction.Persist, SessionAction.UpdateNotification),
                "锁屏失败后用户自行熄屏：失败状态解除"
            )
        }
    }

    // ------------------------------------------------------------------
    // USER_PRESENT：keyguard 真正消失，唯一允许创建会话的入口
    // ------------------------------------------------------------------
    private fun onUserPresent(cur: SessionSnapshot, ev: SessionEvent.UserPresent): Transition {
        if (ev.keyguardLocked) {
            // 过期广播：keyguard 实际仍在锁
            return Transition(cur, note = "USER_PRESENT 到达但 keyguard 仍锁定，忽略")
        }
        return when (cur.state) {
            SessionState.NO_SESSION -> Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                )
            )

            SessionState.PENDING_SELECTION -> Transition(
                cur,
                // 幂等：待选择中重复的解锁广播只要求控制器保证悬浮层存在
                listOf(SessionAction.ShowSelectionOverlay),
                "待选择中重复 USER_PRESENT，保持待选择"
            )

            SessionState.TIMING -> Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.CancelAlarm,
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "计时中出现 USER_PRESENT：此前漏记熄屏，旧会话作废并重新选择"
            )

            SessionState.UNLIMITED -> Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "本次不限中出现 USER_PRESENT：此前漏记熄屏，重新选择"
            )

            SessionState.LOCK_REQUESTED -> Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "锁屏请求未生效即被解锁：重新选择"
            )

            SessionState.LOCK_FAILED -> Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "锁屏失败后解锁：重新选择"
            )
        }
    }

    // ------------------------------------------------------------------
    // 到期闹钟：必须核对会话标识
    // ------------------------------------------------------------------
    private fun onAlarmFired(cur: SessionSnapshot, ev: SessionEvent.AlarmFired): Transition {
        if (cur.state != SessionState.TIMING) {
            return Transition(cur, note = "闹钟到达但状态=${cur.state}，忽略（旧事件）")
        }
        if (ev.firedSessionId != cur.sessionId) {
            return Transition(
                cur,
                note = "闹钟会话标识 ${ev.firedSessionId} ≠ 当前 ${cur.sessionId}，忽略旧事件"
            )
        }
        if (ev.nowElapsed < cur.deadlineElapsed - ALARM_TOLERANCE_MS) {
            // 闹钟提前触发：按原截止时间重新排定
            return Transition(
                cur,
                listOf(SessionAction.ScheduleAlarm(cur.sessionId, cur.deadlineElapsed)),
                "闹钟提前触发（剩余 ${cur.deadlineElapsed - ev.nowElapsed}ms），重新排定"
            )
        }
        return Transition(
            cur.copy(state = SessionState.LOCK_REQUESTED),
            listOf(
                SessionAction.Persist,
                SessionAction.RequestLockNow,
                SessionAction.ScheduleLockWatchdog(LOCK_WATCHDOG_MS),
                SessionAction.UpdateNotification
            ),
            "到期：发出锁屏请求"
        )
    }

    // ------------------------------------------------------------------
    // 选择：只对待选择会话有效，且标识必须一致
    // ------------------------------------------------------------------
    private fun onSelected(cur: SessionSnapshot, ev: SessionEvent.Selected): Transition {
        if (cur.state != SessionState.PENDING_SELECTION) return Transition(cur)
        if (ev.pendingSessionId != cur.sessionId) {
            return Transition(
                cur,
                note = "选择对应会话 ${ev.pendingSessionId} ≠ 当前 ${cur.sessionId}，忽略"
            )
        }
        if (!ev.unlimited) {
            val valid = ev.durationMs in 1000..MAX_SESSION_MS
            if (!valid) {
                return Transition(
                    cur,
                    note = "选择时长非法（durationMs=${ev.durationMs}），保持待选择"
                )
            }
        }
        return if (ev.unlimited) {
            Transition(
                cur.copy(
                    state = SessionState.UNLIMITED,
                    startedElapsed = ev.nowElapsed,
                    deadlineElapsed = 0L
                ),
                listOf(
                    SessionAction.Persist,
                    SessionAction.HideSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "选择：本次不限"
            )
        } else {
            val deadline = addSaturating(ev.nowElapsed, ev.durationMs)
            Transition(
                cur.copy(
                    state = SessionState.TIMING,
                    startedElapsed = ev.nowElapsed,
                    deadlineElapsed = deadline
                ),
                listOf(
                    SessionAction.Persist,
                    SessionAction.HideSelectionOverlay,
                    SessionAction.ScheduleAlarm(cur.sessionId, deadline),
                    SessionAction.UpdateNotification
                ),
                "选择：计时 ${ev.durationMs}ms，截止=$deadline"
            )
        }
    }

    // ------------------------------------------------------------------
    // 锁屏结果与看门狗
    // ------------------------------------------------------------------
    private fun onLockOutcome(cur: SessionSnapshot, ev: SessionEvent.LockOutcome): Transition {
        return when (cur.state) {
            SessionState.LOCK_REQUESTED ->
                if (ev.success) {
                    Transition(cur, note = "lockNow() 已返回，等待系统熄屏确认")
                } else {
                    Transition(
                        cur.copy(state = SessionState.LOCK_FAILED, lockError = ev.error ?: "未知错误"),
                        listOf(SessionAction.Persist, SessionAction.UpdateNotification),
                        "lockNow() 失败：${ev.error}"
                    )
                }

            SessionState.LOCK_FAILED ->
                if (ev.success) {
                    // 重试成功：回到锁屏请求状态继续等熄屏确认
                    Transition(
                        cur.copy(state = SessionState.LOCK_REQUESTED, lockError = null),
                        listOf(
                            SessionAction.Persist,
                            SessionAction.ScheduleLockWatchdog(LOCK_WATCHDOG_MS),
                            SessionAction.UpdateNotification
                        ),
                        "锁屏重试成功"
                    )
                } else {
                    Transition(cur, note = "锁屏重试仍失败：${ev.error}")
                }

            else -> Transition(cur, note = "LockOutcome 到达但状态=${cur.state}，忽略")
        }
    }

    private fun onLockWatchdog(cur: SessionSnapshot, ev: SessionEvent.LockWatchdog): Transition {
        if (cur.state != SessionState.LOCK_REQUESTED) return Transition(cur)
        return Transition(
            cur.copy(
                state = SessionState.LOCK_FAILED,
                lockError = "锁屏请求后 ${SessionMachine.LOCK_WATCHDOG_MS / 1000}s 内未确认熄屏"
            ),
            listOf(SessionAction.Persist, SessionAction.UpdateNotification),
            "锁屏看门狗超时（elapsed=${ev.nowElapsed}）"
        )
    }

    // ------------------------------------------------------------------
    // 恢复：开机、服务重启、进程恢复后核对当前设备状态
    // ------------------------------------------------------------------
    private fun onRecover(cur: SessionSnapshot, ev: SessionEvent.Recover): Transition {
        if (!ev.sameBoot) {
            return Transition(
                cleared(),
                listOf(
                    SessionAction.CancelAlarm,
                    SessionAction.CancelLockWatchdog,
                    SessionAction.HideSelectionOverlay,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification
                ),
                "恢复检查：检测到重启，旧会话（id=${cur.sessionId}）作废"
            )
        }
        val locked = !ev.interactive || ev.keyguardLocked
        if (locked) {
            if (cur.state == SessionState.NO_SESSION) return Transition(cur)
            return Transition(
                cleared(),
                listOf(
                    SessionAction.CancelAlarm,
                    SessionAction.CancelLockWatchdog,
                    SessionAction.HideSelectionOverlay,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification
                ),
                "恢复检查：当前已锁屏/非交互，会话（state=${cur.state}）结束"
            )
        }
        // 已交互且未锁屏
        return when (cur.state) {
            SessionState.NO_SESSION -> Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "恢复检查：已解锁但无会话，补上待选择"
            )

            SessionState.PENDING_SELECTION -> Transition(
                cur,
                listOf(SessionAction.ShowSelectionOverlay),
                "恢复检查：待选择中，重新显示选择层"
            )

            SessionState.TIMING -> {
                if (ev.nowElapsed + ALARM_TOLERANCE_MS < cur.deadlineElapsed) {
                    Transition(
                        cur,
                        listOf(
                            SessionAction.ScheduleAlarm(cur.sessionId, cur.deadlineElapsed),
                            SessionAction.UpdateNotification
                        ),
                        "恢复检查：计时会话沿用原截止时间 ${cur.deadlineElapsed}（剩余 ${cur.deadlineElapsed - ev.nowElapsed}ms）"
                    )
                } else {
                    Transition(
                        cur.copy(state = SessionState.LOCK_REQUESTED),
                        listOf(
                            SessionAction.Persist,
                            SessionAction.RequestLockNow,
                            SessionAction.ScheduleLockWatchdog(LOCK_WATCHDOG_MS),
                            SessionAction.UpdateNotification
                        ),
                        "恢复检查：计时会话已过期，执行到期锁屏"
                    )
                }
            }

            SessionState.UNLIMITED -> Transition(
                cur,
                listOf(SessionAction.UpdateNotification),
                "恢复检查：本次不限继续有效"
            )

            SessionState.LOCK_REQUESTED -> Transition(
                cur.copy(state = SessionState.LOCK_REQUESTED),
                listOf(
                    SessionAction.Persist,
                    SessionAction.RequestLockNow,
                    SessionAction.ScheduleLockWatchdog(LOCK_WATCHDOG_MS),
                    SessionAction.UpdateNotification
                ),
                "恢复检查：锁屏请求未确认且屏幕已亮，重新请求锁屏"
            )

            SessionState.LOCK_FAILED -> Transition(
                pending(ev.nextSessionId, elapsedZero()),
                listOf(
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "恢复检查：锁屏失败且屏幕已亮，重新选择"
            )
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private fun pending(id: Long, now: Long): SessionSnapshot = SessionSnapshot(
        state = SessionState.PENDING_SELECTION,
        sessionId = id,
        startedElapsed = now,
        deadlineElapsed = 0L,
        lockError = null
    )

    private fun cleared(): SessionSnapshot = SessionSnapshot(
        state = SessionState.NO_SESSION,
        sessionId = 0L,
        startedElapsed = 0L,
        deadlineElapsed = 0L,
        lockError = null
    )

    /** 恢复/新建会话时尚无“当前时刻”的场景占位：由调用方在执行 Persist 前不必修正，
     *  startedElapsed 仅用于展示，等待选择结束才写入真实开始时刻 */
    private fun elapsedZero(): Long = 0L

    private fun addSaturating(a: Long, b: Long): Long =
        try {
            Math.addExact(a, b)
        } catch (e: ArithmeticException) {
            Long.MAX_VALUE
        }
}
