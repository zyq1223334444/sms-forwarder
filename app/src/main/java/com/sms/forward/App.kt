package com.sms.forward

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.sms.forward.mail.MailConfig
import com.sms.forward.service.KeepAliveService
import com.sms.forward.util.SecurePrefs
import com.sms.forward.util.ShizukuGuard
import com.sms.forward.work.ReconcileWorker

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注册周期补偿（15 分钟）；无 READ_SMS 权限时 worker 内部自动跳过
        ReconcileWorker.schedule(this)
        // 打开应用即补偿扫描一次（README 承诺的"打开应用时扫描"；历史版本只做了周期任务）。
        // 节流 10 分钟：进程被短信广播拉起也会走这里，不能每次都扫库。
        reconcileOnLaunch()
        // 注册 Shizuku 守护（每 5 分钟保活；内部判断自动开网开关，未开启时不运行）
        ShizukuGuard.schedule(this)
        // 应用被强制停止/重启后自动恢复前台保活（若转发已启用），
        // 避免用户重新打开应用后仍需手动进设置页才能恢复保活。
        if (SecurePrefs.getBoolean(this, MailConfig.KEY_ENABLED, false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { KeepAliveService.start(this) }
            }, 3000L)
        }
        // 打开应用时立即检查一次 Shizuku 状态（不必等守护的 60 秒首跑）：
        // 活着→立刻加护；死了→立刻发通知提醒（自动开网开关关闭时内部直接返回）
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { ShizukuGuard.checkAndProtect(this) }
        }, 5000L)
    }

    /**
     * 打开应用即补一次扫描：把"应用被杀期间收到的短信"立刻补发出去。
     * 节流 [LAUNCH_RECONCILE_THROTTLE_MS]，避免进程被短信广播频繁拉起时反复扫库。
     * 只在转发已启用时执行。
     */
    private fun reconcileOnLaunch() {
        if (!SecurePrefs.getBoolean(this, MailConfig.KEY_ENABLED, false)) return
        runCatching {
            val last = SecurePrefs.getLong(this, KEY_LAST_LAUNCH_RECONCILE, 0L)
            val now = System.currentTimeMillis()
            if (now - last < LAUNCH_RECONCILE_THROTTLE_MS) return
            SecurePrefs.putLong(this, KEY_LAST_LAUNCH_RECONCILE, now)
            ReconcileWorker.runOnce(this)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: App

        private const val KEY_LAST_LAUNCH_RECONCILE = "last_launch_reconcile_ts"
        private const val LAUNCH_RECONCILE_THROTTLE_MS = 10 * 60 * 1000L
    }
}
