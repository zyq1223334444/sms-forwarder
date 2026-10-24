package com.sms.forward.data

/**
 * 短信记录状态机：
 * PENDING → SENDING → SENT / DRY_RUN / FAILED_RETRYABLE / FAILED_PERMANENT
 * FAILED_RETRYABLE → SENT / FAILED_PERMANENT（超重试次数）
 * FAILED_PERMANENT →（手动重试）→ PENDING
 */
enum class SmsStatus {
    PENDING,
    SENDING,
    DRY_RUN,
    SENT,
    FAILED_RETRYABLE,
    FAILED_PERMANENT
}
