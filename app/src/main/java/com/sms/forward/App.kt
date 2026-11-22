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

    companion object {
        @Volatile
        lateinit var instance: App
    }
}
