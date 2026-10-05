package com.local.unlocksession.util

import java.util.Locale

object Format {
    /** 毫秒 → "mm:ss" 或 "h:mm:ss" */
    fun remaining(ms: Long): String {
        val total = if (ms < 0) 0 else ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun minutesLabel(minutes: Int): String = "$minutes 分钟"
}
