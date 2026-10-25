package com.sms.forward.mail

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.internet.AddressException

/**
 * SMTP 失败分类（M5 完成；v3.2.1 增加网络类）：
 * - 永久失败（鉴权失败 / 地址错误 / SMTP 5xx 明确拒绝）→ FAILED_PERMANENT，停止重试；
 * - 网络类（连接失败 / DNS 解析失败 / 超时 / 连接中断）→ FAILED_NETWORK，
 *   长期可重试：无网期间以退避节奏持续尝试，网络恢复后自动补发（不设 5 次上限）；
 * - 其他可重试（SMTP 4xx 等）→ FAILED_RETRYABLE，指数退避，5 次上限。
 */
object MailErrorClassifier {

    const val TYPE_RETRYABLE = "RETRYABLE"
    const val TYPE_PERMANENT = "PERMANENT"
    const val TYPE_NETWORK = "NETWORK"

    fun classify(throwable: Throwable): String {
        var cur: Throwable? = throwable
        while (cur != null) {
            when (cur) {
                // 鉴权失败：授权码错误/账号错误 → 永久
                is AuthenticationFailedException -> return TYPE_PERMANENT
                // 地址解析失败：收件人/发件人地址非法 → 永久
                is AddressException -> return TYPE_PERMANENT
                // 网络层：连接失败 / DNS / 超时 / 连接中断 → 网络类（长期可重试）
                is UnknownHostException, is ConnectException, is SocketTimeoutException,
                is SocketException -> return TYPE_NETWORK
            }
            // SMTP 应答码特征：5xx 永久拒绝，4xx 临时失败
            if (cur is MessagingException || cur is SendFailedException) {
                val msg = cur.message?.uppercase() ?: ""
                if (msg.contains(" 5") || msg.startsWith("5")) return TYPE_PERMANENT
                if (msg.contains(" 4") || msg.startsWith("4")) return TYPE_RETRYABLE
            }
            cur = cur.cause
        }
        // 兜底：宁可重试，不静默丢信
        return TYPE_RETRYABLE
    }

    /**
     * 错误信息脱敏：
     * - 截断到 200 字符；
     * - 过滤疑似授权码片段（形如连续 8-32 位字母数字且出现在常见关键词后）。
     */
    fun sanitizeMessage(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val cleaned = message
            .replace(Regex("(?i)(password|authorization|auth)[=: ]+[\\w.-]{6,}"), "$1=***")
            .take(200)
        return cleaned
    }
}
