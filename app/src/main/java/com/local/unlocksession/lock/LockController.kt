package com.local.unlocksession.lock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.local.unlocksession.diag.Diagnostics

/**
 * 系统锁屏执行器：最小设备管理员（force-lock）+ DevicePolicyManager.lockNow()。
 *
 * - 不修改、不读取、不代填锁屏密码；
 * - lockNow() 没抛异常只能证明请求已发出，真实结果由后续熄屏广播确认；
 * - 管理员失效时如实报告，不静默降级为其他“伪锁屏”。
 */
object LockController {

    fun componentName(context: Context): ComponentName =
        ComponentName(context, com.local.unlocksession.service.AdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return try {
            dpm.isAdminActive(componentName(context))
        } catch (e: Exception) {
            false
        }
    }

    /** 引导用户到系统页面激活设备管理员 */
    fun adminActivationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "解锁限时需要设备管理员权限，才能在时间用完时强制锁屏（仅使用 force-lock 一项策略）。"
            )
        }

    /** 系统是否有安全锁屏凭据（密码/图案/指纹）。无凭据时锁屏语义弱化，需提示用户 */
    fun isKeyguardSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            ?: return false
        return km.isKeyguardSecure
    }

    fun isKeyguardLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            ?: return true
        return km.isKeyguardLocked
    }

    /** 设备是否处于需要系统凭据的强锁定状态（密码解锁过渡期判定用） */
    fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            ?: return false
        return try {
            km.isDeviceLocked
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 发出锁屏请求。返回 (已发出, 错误)。
     * 这里只证明调用未抛异常；确认熄屏由状态机在收到 SCREEN_OFF 后完成。
     */
    fun requestLock(context: Context, diag: Diagnostics): Pair<Boolean, String?> {
        if (!isAdminActive(context)) {
            val err = "设备管理员未启用"
            diag.log("LOCK", "lockNow 拒绝：$err")
            return false to err
        }
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            diag.log("LOCK", "lockNow() 已调用（请求已发出，待熄屏确认） elapsed=${SystemClock.elapsedRealtime()}")
            true to null
        } catch (e: Exception) {
            diag.log("LOCK", "lockNow() 异常：${e.javaClass.simpleName}: ${e.message}")
            false to "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
