package com.sms.forward.data

import java.security.MessageDigest

/**
 * 幂等去重键生成。
 *
 * 关键约束：广播路径与系统短信库（补偿）路径对同一条短信必须算出相同的 key。
 * 早期版本把 subscriptionId 与分片数也算进 key，两条路径口径天然不同：
 * - 广播路径的分片数 = intent 里的 SmsMessage 个数（长短信为 2~3），补偿路径固定 1；
 * - 广播路径的 subscriptionId 取自 intent extra，部分 ROM 读不到（null → -1），
 *   补偿路径取自短信库的 subscription_id（真实值）。
 * 结果同一条短信算出两个 key → 重复入库 → 同一短信发两封邮件。
 *
 * 因此 from v1.4.1 起 key 只由「规范化发件人 | 系统短信时间 | 正文」构成；
 * 卡槽/运营商属于展示信息，写在实体字段里，不参与去重。
 */
object DedupKey {

    /**
     * 规范化发件人号码：
     * 1. 去除所有空格、横线、括号等可见分隔符；
     * 2. 中国号码统一规则：`+86` 前缀与无前缀视为同一号码（如 +86138xxxx1234 == 138xxxx1234）；
     * 3. 其他国际号码保留 + 前缀（E.164 原样）。
     */
    fun normalizeSender(raw: String?): String {
        val cleaned = raw?.replace(Regex("[\\s\\-()（）]"), "")?.trim().orEmpty()
        if (cleaned.isEmpty()) return cleaned
        return if (cleaned.startsWith("+86") && cleaned.length > 3) {
            cleaned.removePrefix("+86")
        } else {
            cleaned
        }
    }

    /**
     * @param senderRaw  原始发件人
     * @param receivedAt 系统短信时间（毫秒）
     * @param subscriptionId 卡 subscriptionId（保留参数以兼容调用点，**不参与 key**）
     * @param body       完整正文（长短信合并后）
     * @param partCount  长短信分片总数（保留参数以兼容调用点，**不参与 key**）
     */
    @Suppress("UNUSED_PARAMETER")
    fun compute(
        senderRaw: String?,
        receivedAt: Long,
        subscriptionId: Int?,
        body: String,
        partCount: Int
    ): String {
        val normalized = normalizeSender(senderRaw)
        val raw = buildString {
            append(normalized)
            append('|')
            append(receivedAt)
            append('|')
            append(body.trim())
        }
        return sha256Hex(raw)
    }

    /** 取 key 前 8 位短哈希，用于邮件正文/Message-ID 的人眼可读去重标识 */
    fun shortHash(uniqueKey: String): String =
        uniqueKey.take(8)

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
