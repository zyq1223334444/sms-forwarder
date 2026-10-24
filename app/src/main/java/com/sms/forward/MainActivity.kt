package com.sms.forward

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sms.forward.databinding.ActivityMainBinding
import com.sms.forward.mail.MailConfig
import com.sms.forward.ui.MiuiGuideActivity
import com.sms.forward.ui.SettingsActivity
import com.sms.forward.ui.StatusActivity

/**
 * 主入口：运行状态总览（M7 完成）。
 * 展示 启用状态 / 干跑模式 / 收件邮箱；入口跳转 设置 / 状态 / 小米引导。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnStatus.setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }
        binding.btnMiuiGuide.setOnClickListener {
            startActivity(Intent(this, MiuiGuideActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatusSummary()
    }

    private fun refreshStatusSummary() {
        val s = MailConfig.load(this)
        val status = when {
            !s.enabled -> "转发未启用"
            s.dryRun -> "干跑模式（不真实发送）"
            else -> "转发已启用"
        }
        val email = if (s.toEmail.isNotBlank()) " → ${maskEmail(s.toEmail)}" else "（未配置收件邮箱）"
        binding.tvStatusSummary.text = "$status$email"
    }

    private fun maskEmail(email: String?): String {
        val s = email.orEmpty()
        val at = s.indexOf('@')
        return if (at > 1) s.substring(0, 1) + "***" + s.substring(at) else s
    }
}
