package com.sms.forward.mail

import com.sms.forward.data.DedupKey
import com.sms.forward.data.SmsEntity
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * 邮件发送器（M5 完成）。
 *
 * 约束：
 * - 固定 Message-ID = <uniqueKey短哈希@sms.forward>，便于服务商/人工识别重复；
 * - 显式设置连接/读/写超时（15s/30s/30s），避免 Worker 卡死；
 * - 日志禁止打印授权码、完整正文、完整收件人；
 * - 干跑模式由 SendQueueWorker 在调用前拦截（此处始终为真实发送实现）。
 */
object MailSender {

    private const val TAG = "MailSender"

    sealed class SendResult {
        data class Success(val messageId: String) : SendResult()
        data class Failure(val permanent: Boolean, val message: String?) : SendResult()
    }

    /**
     * 真实发送：SMTP over SSL / STARTTLS / 明文。
     * @throws 由调用方捕获；此处返回分类结果
     */
    fun send(entity: SmsEntity, settings: MailConfig.MailSettings): SendResult {
        return try {
            val props = buildProps(settings)
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(settings.username, settings.authCode)
            })
            // 调试日志只打到内存，不输出敏感内容
            val messageId = "<${DedupKey.shortHash(entity.uniqueKey)}@sms.forward>"
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(settings.username))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(settings.toEmail, false))
                subject = buildSubject(entity, settings)
                setText(buildBody(entity, settings), "UTF-8")
                setHeader("Message-ID", messageId)
            }
            Transport.send(msg)
            SendResult.Success(messageId)
        } catch (t: Throwable) {
            // 完整堆栈仅进 logcat（不落库、不显示给用户），用于排查网络/SSL 层原因
            Log.e(TAG, "SMTP 发送异常: host=${settings.host}:${settings.port} enc=${settings.encryption}", t)
            val type = MailErrorClassifier.classify(t)
            SendResult.Failure(
                permanent = type == MailErrorClassifier.TYPE_PERMANENT,
                message = MailErrorClassifier.sanitizeMessage(t.message ?: t.javaClass.simpleName)
            )
        }
    }

    private fun buildProps(settings: MailConfig.MailSettings): Properties {
        val props = Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.writetimeout", "30000")
            when (settings.encryption.uppercase()) {
                "SSL" -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", settings.port.toString())
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.socketFactory.fallback", "false")
                }
                "STARTTLS" -> put("mail.smtp.starttls.enable", "true")
                // NONE：明文，不设置额外项
            }
        }
        return props
    }

    // ---------- 邮件模板（§7） ----------

    fun buildSubject(entity: SmsEntity, settings: MailConfig.MailSettings): String {
        val number = displayNumber(entity, settings)
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entity.receivedAt))
        val simTag = buildString {
            if (entity.simSlot != null) {
                append("[卡").append(entity.simSlot + 1)
                if (!entity.carrierName.isNullOrBlank()) append("·").append(entity.carrierName)
                append("] ")
            }
        }
        return "[短信转发]${simTag}来自 $number · $time"
    }

    fun buildBody(entity: SmsEntity, settings: MailConfig.MailSettings): String {
        val fullTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entity.receivedAt))
        return buildString {
            appendLine(entity.body)
            appendLine()
            appendLine("----")
            appendLine("接收时间: $fullTime")
            appendLine("发件号码: ${displayNumber(entity, settings)}")
            if (entity.simSlot != null) {
                appendLine("卡槽: 卡${entity.simSlot + 1}${entity.carrierName?.let { " ($it)" } ?: ""}")
            }
            if (entity.multipartIncomplete) appendLine("注意: 长短信分片不完整")
            appendLine("消息ID: ${DedupKey.shortHash(entity.uniqueKey)}")
        }
    }

    /** 主题/正文号码展示：默认脱敏，开关显示完整 */
    fun displayNumber(entity: SmsEntity, settings: MailConfig.MailSettings): String =
        if (settings.showFullNumber) entity.senderRaw
        else maskNumber(entity.senderRaw)

    private fun maskNumber(raw: String?): String {
        val s = raw.orEmpty()
        return if (s.length >= 7) s.substring(0, 3) + "****" + s.substring(s.length - 4)
        else s
    }
}
