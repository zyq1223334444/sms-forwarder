package com.sms.forward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sms.forward.work.ReconcileWorker

/**
 * 开机自启补偿（M6 完成）：
 * 开机后触发一次一次性补偿扫描，补发重启期间错过的短信。
 *
 * 注意：MIUI 等系统"强制停止"应用后静态广播不会唤起，
 * 必须手动打开一次应用才会恢复——属预期行为，引导页有说明。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "BOOT_COMPLETED：触发开机补偿扫描")
        ReconcileWorker.runOnce(context.applicationContext)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
