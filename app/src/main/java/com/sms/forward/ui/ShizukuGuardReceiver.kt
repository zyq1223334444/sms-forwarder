package com.sms.forward.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sms.forward.util.ShizukuGuard

/**
 * 接收 AlarmManager 周期广播，执行 Shizuku 守护（见 ShizukuGuard）。
 */
class ShizukuGuardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ShizukuGuard.ACTION_CHECK) return
        ShizukuGuard.checkAndProtect(context)
    }
}
