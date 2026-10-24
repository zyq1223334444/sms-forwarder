package com.sms.forward.mail

/**
 * 主流邮箱 SMTP 预设表（设置页下拉自动填充）。
 * 首版测试邮箱：QQ 邮箱（smtp.qq.com:465 SSL + 授权码）。
 */
object MailPresets {

    data class Preset(
        val label: String,
        val host: String,
        val port: Int,
        val encryption: String // SSL / STARTTLS / NONE
    )

    val PRESETS: List<Preset> = listOf(
        Preset("QQ 邮箱", "smtp.qq.com", 465, "SSL"),
        Preset("163 邮箱", "smtp.163.com", 465, "SSL"),
        Preset("126 邮箱", "smtp.126.com", 465, "SSL"),
        Preset("139 邮箱", "smtp.139.com", 465, "SSL"),
        Preset("新浪邮箱", "smtp.sina.com", 465, "SSL"),
        Preset("搜狐邮箱", "smtp.sohu.com", 465, "SSL"),
        Preset("Gmail", "smtp.gmail.com", 465, "SSL"),
        Preset("Outlook/Hotmail", "smtp.office365.com", 587, "STARTTLS"),
        Preset("自定义", "", 465, "SSL")
    )

    /** 预设说明（设置页展示，含授权码获取指引关键字） */
    val PRESET_NOTES: Map<String, String> = mapOf(
        "QQ 邮箱" to "网页端 设置→账户→开启 SMTP 服务，获取授权码；注意单日发信量上限",
        "163 邮箱" to "网页端 设置→POP3/SMTP/IMAP→开启 SMTP，获取授权码",
        "126 邮箱" to "网页端 设置→POP3/SMTP/IMAP→开启 SMTP，获取授权码",
        "139 邮箱" to "网页端 设置→客户端密码→生成应用专用密码",
        "Gmail" to "需开启两步验证并生成应用专用密码；网络可达性不稳定",
        "Outlook/Hotmail" to "部分账号基本认证已不可用，可能需要 OAuth，首版不承诺可用"
    )
}
