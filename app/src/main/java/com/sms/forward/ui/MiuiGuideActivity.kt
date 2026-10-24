package com.sms.forward.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sms.forward.databinding.ActivityMiuiGuideBinding

/**
 * MIUI / HyperOS 保活设置引导页（M7 完成）：
 * 逐项引导自启动、电池白名单、通知、WLAN 等设置；说明强制停止后的预期行为。
 */
class MiuiGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMiuiGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMiuiGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
