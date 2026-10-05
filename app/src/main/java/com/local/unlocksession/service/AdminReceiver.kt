package com.local.unlocksession.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.local.unlocksession.diag.Diagnostics

/** 最小设备管理员：仅 force-lock（见 res/xml/admin_policies.xml），用于 lockNow() */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Diagnostics.get(context).log("ADMIN", "设备管理员已启用（force-lock）")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Diagnostics.get(context).log("ADMIN", "设备管理员已禁用：到期锁屏能力失效，需重新启用")
    }
}
