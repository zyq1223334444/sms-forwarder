package com.sms.forward

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.sms.forward.mail.MailConfig
import com.sms.forward.service.KeepAliveService
import com.sms.forward.util.SecurePrefs
import com.sms.forward.work.ReconcileWorker

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注册周期补偿（15 分钟）；无 READ_SMS 权限时 worker 内部自动跳过
        ReconcileWorker.schedule(this)
        // 应用被强制停止/重启后自动恢复前台保活（若转发已启用），
        // 避免用户重新打开应用后仍需手动进设置页才能恢复保活。
        if (SecurePrefs.getBoolean(this, MailConfig.KEY_ENABLED, false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { KeepAliveService.start(this) }
            }, 3000L)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: App
    }
}
