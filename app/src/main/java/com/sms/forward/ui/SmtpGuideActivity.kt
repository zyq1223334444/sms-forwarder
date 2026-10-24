package com.sms.forward.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sms.forward.databinding.ActivitySmtpGuideBinding

/**
 * SMTP 授权码获取教程页：各主流邮箱（QQ/163/126/139/新浪/搜狐/Gmail/Outlook）的
 * 网页端开启 SMTP 与获取授权码的详细步骤，附 SMTP 配置与常见问题。
 */
class SmtpGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmtpGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmtpGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
