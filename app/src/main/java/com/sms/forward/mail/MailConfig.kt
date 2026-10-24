package com.sms.forward.mail

import android.content.Context
import com.sms.forward.util.SecurePrefs

/**
 * 邮件配置读取（M5 填充校验与完整读写）。
 * 敏感字段（授权码）走 SecurePrefs 加密存储；非敏感字段（主机/端口/收件人等）
 * 也可统一走 SecurePrefs，便于一处管理。
 */
object MailConfig {

    const val KEY_ENABLED = "mail_enabled"
    const val KEY_DRY_RUN = "dry_run"                 // 干跑模式开关，默认 true
    const val KEY_SMTP_HOST = "smtp_host"
    const val KEY_SMTP_PORT = "smtp_port"
    const val KEY_SMTP_ENCRYPTION = "smtp_encryption" // SSL / STARTTLS / NONE
    const val KEY_USERNAME = "smtp_username"          // 发件邮箱账号
    const val KEY_AUTH_CODE = "smtp_auth_code"        // 授权码（加密存储）
    const val KEY_TO_EMAIL = "to_email"               // 目标邮箱
    const val KEY_SHOW_FULL_NUMBER = "show_full_number" // 主题是否显示完整号码，默认 false
    const val KEY_SEND_INTERVAL_MS = "send_interval_ms" // 限速间隔，默认 4000ms
    const val KEY_RECONCILE_BASELINE_ID = "reconcile_baseline_id" // 补偿基线最大 _id

    data class MailSettings(
        val enabled: Boolean,
        val dryRun: Boolean,
        val host: String,
        val port: Int,
        val encryption: String,
        val username: String,
        val authCode: String,
        val toEmail: String,
        val showFullNumber: Boolean,
        val sendIntervalMs: Long
    )

    fun isConfigured(context: Context): Boolean {
        val s = load(context)
        return s.host.isNotBlank() && s.username.isNotBlank() && s.authCode.isNotBlank() && s.toEmail.isNotBlank()
    }

    fun save(context: Context, s: MailSettings) {
        val p = SecurePrefs
        p.putBoolean(context, KEY_ENABLED, s.enabled)
        p.putBoolean(context, KEY_DRY_RUN, s.dryRun)
        p.putString(context, KEY_SMTP_HOST, s.host)
        p.putInt(context, KEY_SMTP_PORT, s.port)
        p.putString(context, KEY_SMTP_ENCRYPTION, s.encryption)
        p.putString(context, KEY_USERNAME, s.username)
        p.putString(context, KEY_AUTH_CODE, s.authCode)
        p.putString(context, KEY_TO_EMAIL, s.toEmail)
        p.putBoolean(context, KEY_SHOW_FULL_NUMBER, s.showFullNumber)
        p.putLong(context, KEY_SEND_INTERVAL_MS, s.sendIntervalMs)
    }

    fun load(context: Context): MailSettings {
        val p = SecurePrefs
        return MailSettings(
            enabled = p.getBoolean(context, KEY_ENABLED, false),
            dryRun = p.getBoolean(context, KEY_DRY_RUN, true),
            host = p.getString(context, KEY_SMTP_HOST, "").orEmpty(),
            port = p.getInt(context, KEY_SMTP_PORT, 465),
            encryption = p.getString(context, KEY_SMTP_ENCRYPTION, "SSL").orEmpty(),
            username = p.getString(context, KEY_USERNAME, "").orEmpty(),
            authCode = p.getString(context, KEY_AUTH_CODE, "").orEmpty(),
            toEmail = p.getString(context, KEY_TO_EMAIL, "").orEmpty(),
            showFullNumber = p.getBoolean(context, KEY_SHOW_FULL_NUMBER, false),
            sendIntervalMs = p.getLong(context, KEY_SEND_INTERVAL_MS, 4000L)
        )
    }
}
