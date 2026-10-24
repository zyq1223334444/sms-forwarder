package com.sms.forward.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sms.forward.databinding.ActivitySettingsBinding
import com.sms.forward.mail.MailConfig
import com.sms.forward.mail.MailPresets
import com.sms.forward.service.KeepAliveService

/**
 * 设置页：
 * - 邮箱类型通过弹窗单选（9 个预设），选择后自动填充 SMTP 配置，避免下拉框交互问题；
 * - 授权码获取教程入口（SmtpGuideActivity）；
 * - 干跑模式开关（默认开）、限速、发送测试；启用时请求权限并联动前台保活。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var selectedPresetLabel: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            Toast.makeText(
                this,
                if (allGranted) "权限已授予"
                else "部分权限未授予，转发可能不完整（详见权限说明）",
                Toast.LENGTH_LONG
            ).show()
            if (binding.switchEnabled.isChecked) {
                KeepAliveService.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEncryptionDropdown()
        binding.btnMailType.setOnClickListener { showMailTypeDialog() }
        binding.btnSmtpGuide.setOnClickListener {
            startActivity(Intent(this, SmtpGuideActivity::class.java))
        }
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnTest.setOnClickListener { testMail() }

        loadConfig()
    }

    /** 邮箱类型弹窗单选 */
    private fun showMailTypeDialog() {
        val presets = MailPresets.PRESETS
        val labels = presets.map { it.label }.toTypedArray()
        val current = selectedPresetLabel ?: presets.firstOrNull()?.label

        AlertDialog.Builder(this)
            .setTitle("选择邮箱类型")
            .setSingleChoiceItems(labels, labels.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                val preset = presets[which]
                selectedPresetLabel = preset.label
                applyPreset(preset)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 应用预设：填充 SMTP 配置并更新按钮文案 */
    private fun applyPreset(preset: MailPresets.Preset) {
        if (preset.host.isNotBlank()) {
            binding.inputSmtpHost.setText(preset.host)
            binding.inputSmtpPort.setText(preset.port.toString())
            binding.inputEncryption.setText(preset.encryption, false)
        } else {
            // 自定义：清空 SMTP 字段让用户填写
            binding.inputSmtpHost.setText("")
            binding.inputSmtpPort.setText("")
            binding.inputEncryption.setText("SSL", false)
        }
        val detail = if (preset.host.isNotBlank()) "${preset.host}:${preset.port} ${preset.encryption}" else "自行填写 SMTP 服务器"
        binding.btnMailType.text = "邮箱类型：${preset.label}（$detail）"
        MailPresets.PRESET_NOTES[preset.label]?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupEncryptionDropdown() {
        val options = arrayOf("SSL", "STARTTLS", "NONE")
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, options)
        binding.inputEncryption.setAdapter(adapter)
    }

    private fun loadConfig() {
        val s = MailConfig.load(this)
        binding.switchEnabled.isChecked = s.enabled
        binding.switchDryRun.isChecked = s.dryRun
        binding.inputSmtpHost.setText(s.host)
        binding.inputSmtpPort.setText(s.port.toString())
        binding.inputEncryption.setText(s.encryption, false)
        if (s.username.isNotBlank()) binding.inputUsername.setText(s.username)
        if (s.authCode.isNotBlank()) binding.inputAuthCode.setText(s.authCode)
        if (s.toEmail.isNotBlank()) binding.inputToEmail.setText(s.toEmail)
        binding.switchFullNumber.isChecked = s.showFullNumber
        binding.inputInterval.setText((s.sendIntervalMs / 1000L).toString())

        // 按已保存配置反向匹配预设名（用于按钮文案）
        val matched = MailPresets.PRESETS.firstOrNull {
            it.host == s.host && it.port == s.port && it.encryption == s.encryption
        }
        selectedPresetLabel = matched?.label
        if (matched != null) {
            binding.btnMailType.text = "邮箱类型：${matched.label}（${matched.host}:${matched.port} ${matched.encryption}）"
        } else if (s.host.isNotBlank()) {
            binding.btnMailType.text = "邮箱类型：自定义（${s.host}:${s.port} ${s.encryption}）"
        }
    }

    private fun collect(): MailConfig.MailSettings? {
        val host = binding.inputSmtpHost.text?.toString()?.trim().orEmpty()
        val port = binding.inputSmtpPort.text?.toString()?.trim().orEmpty().toIntOrNull() ?: 465
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val authCode = binding.inputAuthCode.text?.toString()?.trim().orEmpty()
        val toEmail = binding.inputToEmail.text?.toString()?.trim().orEmpty()
        val intervalSec = binding.inputInterval.text?.toString()?.trim().orEmpty().toIntOrNull() ?: 4

        if (host.isBlank() || username.isBlank() || authCode.isBlank() || toEmail.isBlank()) {
            Toast.makeText(this, "请完整填写 SMTP 服务器、账号、授权码与收件邮箱", Toast.LENGTH_LONG).show()
            return null
        }
        return MailConfig.MailSettings(
            enabled = binding.switchEnabled.isChecked,
            dryRun = binding.switchDryRun.isChecked,
            host = host,
            port = port,
            encryption = binding.inputEncryption.text?.toString()?.trim().orEmpty().ifBlank { "SSL" },
            username = username,
            authCode = authCode,
            toEmail = toEmail,
            showFullNumber = binding.switchFullNumber.isChecked,
            sendIntervalMs = intervalSec.coerceIn(1, 300) * 1000L
        )
    }

    private fun saveConfig() {
        val settings = collect() ?: return
        MailConfig.save(this, settings)

        if (settings.enabled) {
            requestSmsPermissions()
            KeepAliveService.start(this)
            Toast.makeText(this, "配置已保存，转发已启用${if (settings.dryRun) "（干跑模式）" else ""}", Toast.LENGTH_SHORT).show()
        } else {
            KeepAliveService.stop(this)
            Toast.makeText(this, "配置已保存，转发已停用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestSmsPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun testMail() {
        val settings = collect() ?: return
        MailConfig.save(this, settings)
        if (settings.dryRun) {
            Toast.makeText(this, "干跑模式开启：测试不会真实发送。关闭干跑开关后重试。", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "已保存。可向任意号码发一条短信验证收信。", Toast.LENGTH_LONG).show()
    }
}
