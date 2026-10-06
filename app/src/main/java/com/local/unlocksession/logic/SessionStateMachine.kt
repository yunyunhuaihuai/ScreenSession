package com.local.unlocksession.logic

/**
 * 解锁限时会话状态机。
 *
 * 设计约束（对应交付说明中的会话规则）：
 * - 所有 UI、广播、服务恢复、闹钟事件都走 reduce()，由 SessionController 在单线程上串行调用；
 * - 任何到期/锁屏/提醒事件必须核对会话标识，旧会话事件一律忽略；
 * - 截止时间一旦设定不再改变（不接受取消/暂停/延长/缩短/转不限）；
 * - 只有 nowElapsed >= deadlineElapsed 才执行到期锁屏：不把任何容差用于提前扣减用户时间（R3）；
 * - 熄屏即结束会话：通话接近黑屏（keyguard 未接管）保持会话，真实锁屏/到期确认必须生效（R5）；
 * - 输入是纯函数：同样输入得到同样输出，便于单测幂等与恢复。
 */
object SessionMachine {

    /** 锁屏看门狗默认延时：lockNow 之后仍未确认熄屏则判失败 */
    const val LOCK_WATCHDOG_MS: Long = 10_000L

    /** 会话时长上限（毫秒）：48 小时，防溢出与误输入 */
    const val MAX_SESSION_MS: Long = DurationInput.MAX_MINUTES * 60_000L

    fun reduce(cur: SessionSnapshot, ev: SessionEvent): Transition = when (ev) {
        is SessionEvent.ScreenOn -> onScreenOn(cur, ev)
        is SessionEvent.ScreenOff -> onScreenOff(cur, ev)
        is SessionEvent.UserPresent -> onUserPresent(cur, ev)
        is SessionEvent.AlarmFired -> onAlarmFired(cur, ev)
        is SessionEvent.AlarmScheduleOutcome -> onAlarmScheduleOutcome(cur, ev)
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
                pending(ev.nextSessionId),
                listOf(
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "screen_on 时已交互未锁屏且无会话：漏事件安全网，进入待选择"
            )
        }
        // 计时/不限中亮屏且 keyguard 处于锁定：说明期间发生过一次我们漏记的真实锁屏，
        // 会话应当已经结束——现在补记结束，等待 USER_PRESENT 开新会话。
        if (cur.isActive && ev.keyguardLocked) {
            return Transition(
                cleared(),
                listOf(
                    SessionAction.CancelAlarm,
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification
                ),
                "活动中亮屏且 keyguard 已锁：补记漏掉的熄屏结束"
            )
        }
        return Transition(cur)
    }

    // ------------------------------------------------------------------
    // SCREEN_OFF：熄屏即结束会话；通话接近黑屏（keyguard 未接管）除外（R5）
    // ------------------------------------------------------------------
    private fun onScreenOff(cur: SessionSnapshot, ev: SessionEvent.ScreenOff): Transition {
        // 到期锁屏确认优先：LOCK_REQUESTED 的熄屏就是锁屏完成，不受通话影响
        if (cur.state == SessionState.LOCK_REQUESTED) {
            return Transition(
                cleared(),
                listOf(
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.UpdateNotification
                ),
                "锁屏请求后确认熄屏：到期锁屏完成"
            )
        }
        // 通话接近黑屏：屏幕黑但 keyguard 未接管、设备未锁定——不是会话结束信号（R5）
        if (ev.inCall && !ev.keyguardLocked && !ev.isDeviceLocked) {
            return Transition(cur, note = "通话接近黑屏（keyguard 未锁定），忽略（state=${cur.state}）")
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

            // LOCK_REQUESTED 已在方法开头处理
            SessionState.LOCK_REQUESTED, SessionState.LOCK_FAILED -> Transition(
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
            // 过期广播：keyguard 实际仍在锁。控制器负责过渡期复核，此处保持忽略。
            return Transition(cur, note = "USER_PRESENT 到达但 keyguard 仍锁定，忽略")
        }
        return when (cur.state) {
            SessionState.NO_SESSION -> Transition(
                pending(ev.nextSessionId),
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

            SessionState.TIMING, SessionState.UNLIMITED -> {
                // R4：活动会话中的 USER_PRESENT 一律视为同一解锁周期的迟到/重复广播。
                // 依据：真实锁屏周期由 SCREEN_OFF（结束会话）/SCREEN_ON（keyguard 已锁时
                // 补记结束）先行处理——只要会话仍处于活动中，说明本进程从未观察到锁屏
                // 周期；"事件来得晚"本身不构成发生过真实锁屏的证据（广播可能迟到、重复，
                // 或由系统在锁屏未发生时补发）。保持原会话与截止时间，防止借此取消到期
                // 任务重新选择加时。
                Transition(
                    cur,
                    note = "USER_PRESENT 到达但会话活动中（未观察到锁屏周期），保持会话与截止时间" +
                        "（started=${cur.startedElapsed}, now=${ev.nowElapsed}）"
                )
            }

            SessionState.LOCK_REQUESTED -> Transition(
                pending(ev.nextSessionId),
                listOf(
                    SessionAction.CancelLockWatchdog,
                    SessionAction.Persist,
                    SessionAction.ShowSelectionOverlay,
                    SessionAction.UpdateNotification
                ),
                "锁屏请求未生效即被解锁：重新选择"
            )

            SessionState.LOCK_FAILED -> Transition(
                pending(ev.nextSessionId),
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
    // 到期闹钟：必须核对会话标识；只有到达截止才锁屏（R3）
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
        if (ev.nowElapsed < cur.deadlineElapsed) {
            // 提前触发：按原截止时间重新排定，绝不提前锁屏（R3）
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
            "到期：发出锁屏请求（now=${ev.nowElapsed} ≥ deadline=${cur.deadlineElapsed}）"
        )
    }

    // ------------------------------------------------------------------
    // 闹钟排定结果（R7）
    // ------------------------------------------------------------------
    private fun onAlarmScheduleOutcome(cur: SessionSnapshot, ev: SessionEvent.AlarmScheduleOutcome): Transition {
        if (cur.state != SessionState.TIMING || ev.sessionId != cur.sessionId) {
            return Transition(cur, note = "闹钟排定结果与当前会话不符，忽略")
        }
        if (ev.ok == cur.alarmScheduled) return Transition(cur)
        return Transition(
            cur.copy(alarmScheduled = ev.ok),
            listOf(SessionAction.Persist, SessionAction.UpdateNotification),
            if (ev.ok) "到期闹钟排定成功" else "到期闹钟排定失败：启用进程内兜底检查并在通知中警示"
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
                    deadlineElapsed = deadline,
                    alarmScheduled = true
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
    // 锁屏结果与看门狗：旧会话回传不消费（R1）
    // ------------------------------------------------------------------
    private fun onLockOutcome(cur: SessionSnapshot, ev: SessionEvent.LockOutcome): Transition {
        if (ev.sessionId != cur.sessionId) {
            return Transition(cur, note = "LockOutcome 会话 ${ev.sessionId} ≠ 当前 ${cur.sessionId}，忽略旧事件")
        }
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
        if (ev.sessionId != cur.sessionId) {
            return Transition(cur, note = "看门狗会话 ${ev.sessionId} ≠ 当前 ${cur.sessionId}，忽略旧事件")
        }
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
    // 恢复：开机、服务重启、进程恢复后核对当前设备状态（R6：新开机+已解锁也补待选择）
    // ------------------------------------------------------------------
    private fun onRecover(cur: SessionSnapshot, ev: SessionEvent.Recover): Transition {
        if (!ev.sameBoot) {
            // 重启：旧开机的一切任务作废，绝不复用旧 elapsedRealtime 截止时间
            val clearedActions = mutableListOf<SessionAction>(
                SessionAction.CancelAlarm,
                SessionAction.CancelLockWatchdog,
                SessionAction.HideSelectionOverlay,
                SessionAction.Persist,
                SessionAction.UpdateNotification
            )
            // R6：若此刻已解锁且允许建会话，直接补上待选择，不等下一次解锁
            return if (ev.interactive && !ev.keyguardLocked && ev.canCreateSession) {
                Transition(
                    pending(ev.nextSessionId),
                    clearedActions + listOf(SessionAction.ShowSelectionOverlay),
                    "恢复检查：检测到重启，旧会话（id=${cur.sessionId}）作废；当前已解锁，补上待选择"
                )
            } else {
                Transition(
                    cleared(),
                    clearedActions,
                    "恢复检查：检测到重启，旧会话（id=${cur.sessionId}）作废" +
                        if (ev.interactive && !ev.keyguardLocked) "（能力未就绪，不建会话）" else ""
                )
            }
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
            SessionState.NO_SESSION -> {
                if (!ev.canCreateSession) {
                    Transition(cur, note = "恢复检查：已解锁但能力未就绪，不创建待选择")
                } else {
                    Transition(
                        pending(ev.nextSessionId),
                        listOf(
                            SessionAction.Persist,
                            SessionAction.ShowSelectionOverlay,
                            SessionAction.UpdateNotification
                        ),
                        "恢复检查：已解锁但无会话，补上待选择"
                    )
                }
            }

            SessionState.PENDING_SELECTION -> Transition(
                cur,
                listOf(SessionAction.ShowSelectionOverlay),
                "恢复检查：待选择中，重新显示选择层"
            )

            SessionState.TIMING -> {
                // R3：只有到达截止才执行到期处理，不用容差提前锁屏
                if (ev.nowElapsed >= cur.deadlineElapsed) {
                    Transition(
                        cur.copy(state = SessionState.LOCK_REQUESTED),
                        listOf(
                            SessionAction.Persist,
                            SessionAction.RequestLockNow,
                            SessionAction.ScheduleLockWatchdog(LOCK_WATCHDOG_MS),
                            SessionAction.UpdateNotification
                        ),
                        "恢复检查：计时会话已过期（now=${ev.nowElapsed} ≥ deadline=${cur.deadlineElapsed}），执行到期锁屏"
                    )
                } else {
                    Transition(
                        cur,
                        listOf(
                            SessionAction.ScheduleAlarm(cur.sessionId, cur.deadlineElapsed),
                            SessionAction.UpdateNotification
                        ),
                        "恢复检查：计时会话沿用原截止时间 ${cur.deadlineElapsed}（剩余 ${cur.deadlineElapsed - ev.nowElapsed}ms）"
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

            SessionState.LOCK_FAILED -> {
                if (!ev.canCreateSession) {
                    Transition(
                        cleared(),
                        listOf(SessionAction.Persist, SessionAction.UpdateNotification),
                        "恢复检查：锁屏失败且屏幕已亮，但能力未就绪，回到无会话"
                    )
                } else {
                    Transition(
                        pending(ev.nextSessionId),
                        listOf(
                            SessionAction.Persist,
                            SessionAction.ShowSelectionOverlay,
                            SessionAction.UpdateNotification
                        ),
                        "恢复检查：锁屏失败且屏幕已亮，重新选择"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private fun pending(id: Long): SessionSnapshot = SessionSnapshot(
        state = SessionState.PENDING_SELECTION,
        sessionId = id,
        startedElapsed = 0L,
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

    private fun addSaturating(a: Long, b: Long): Long =
        try {
            Math.addExact(a, b)
        } catch (e: ArithmeticException) {
            Long.MAX_VALUE
        }
}
