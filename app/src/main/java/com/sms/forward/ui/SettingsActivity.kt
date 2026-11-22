package com.sms.forward.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sms.forward.databinding.ActivitySettingsBinding
import com.sms.forward.mail.MailConfig
import com.sms.forward.mail.MailPresets
import com.sms.forward.service.KeepAliveService
import com.sms.forward.sim.SimSlotDetector
import com.sms.forward.util.NetworkAutoToggler
import com.sms.forward.util.SecurePrefs
import com.sms.forward.util.ShizukuGuard
import java.io.File

/**
 * 设置页：
 * - 邮箱类型通过弹窗单选（9 个预设），选择后自动填充 SMTP 配置，避免下拉框交互问题；
 * - 授权码获取教程入口（SmtpGuideActivity）；
 * - 干跑模式开关（默认开）、限速、发送测试；启用时请求权限并联动前台保活；
 * - "自动开启网络"开关：首次打开时申请网络权限，并检测 Shizuku（Android 11+ 系统禁止
 *   应用直接开关网络，需 Shizuku 授权）——未安装引导下载、已装未激活引导去激活。
 */
class SettingsActivity : AppCompatActivity() {

    /** Shizuku 应用包名 */
    private companion object {
        const val TAG = "SettingsActivity"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases"
        const val SHIZUKU_COOLMARKET_URI = "coolmarket://search?keyword=Shizuku"
    }

    private lateinit var binding: ActivitySettingsBinding
    private var selectedPresetLabel: String? = null

    /** 防止 loadConfig() 初始赋值开关时误触发监听器（用户已保存开启时会弹提示） */
    private var loadingConfig = false

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

    /** 网络自动开关权限（首次打开开关时申请；拒绝不影响 WiFi 自动开关主功能） */
    private val networkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val allGranted = grants.values.all { it }
            Toast.makeText(
                this,
                if (allGranted) "网络控制权限已授予"
                else "WiFi 自动开关仍可用；移动数据自动开关受系统限制，若无法自动开启请保持 WiFi 或手动开启移动数据",
                Toast.LENGTH_LONG
            ).show()
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
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 首次打开"自动开网络"开关时：申请网络控制权限 + 检测 Shizuku 并引导；
        // 同时注册/取消 Shizuku 守护（每 5 分钟检测，停止时通知提醒）
        binding.switchAutoNetwork.setOnCheckedChangeListener { _, checked ->
            ShizukuGuard.schedule(this)
            if (checked && !loadingConfig) {
                requestNetworkPermissions()
                checkShizuku()
            }
        }

        loadConfig()
    }

    /**
     * 检测 Shizuku：未安装 → 弹窗引导下载并给出完整安装/激活步骤；已安装未激活 →
     * 弹窗给出完整激活步骤；已激活 → 提示可用。Android 11+ 系统禁止应用直接开关网络，
     * Shizuku 是唯一无 Root 通道，缺失时该功能无法自动生效，必须明确告知用户。
     */
    private fun checkShizuku() {
        if (NetworkAutoToggler.shizukuAvailable()) {
            Toast.makeText(this, "Shizuku 已就绪，自动开网络功能可用", Toast.LENGTH_SHORT).show()
            return
        }
        val installed = isShizukuInstalled()
        // Shizuku 13.x 的激活命令是 App 安装目录下的 libshizuku.so（路径随安装变化，动态获取）；
        // 获取不到时引导用户以 Shizuku App 内「查看指令」显示的命令为准。
        val adbCmd = shizukuAdbCommand()
        val cmdLine = if (adbCmd != null) {
            "   $adbCmd\n" +
                "   （若提示找不到文件，请以 Shizuku App 内「查看指令」显示的命令为准）"
        } else {
            "   （打开 Shizuku 应用 → 点「查看指令」，复制显示的命令执行）"
        }
        val message = if (installed) {
            "已检测到 Shizuku，但尚未激活或未授权本应用。请按以下步骤操作：\n\n" +
                "1. 手机开启「开发者选项 → USB 调试」，用数据线连接电脑；\n" +
                "2. 电脑上执行一次：\n" +
                "$cmdLine\n" +
                "3. 回到手机打开 Shizuku 应用，点击「启动」（开始）；\n" +
                "4. 在 Shizuku 的「已授权的应用」列表中找到「短信转发」，打开授权开关；\n" +
                "5. 返回本应用，此功能即自动生效。"
        } else {
            "「自动开启网络」依赖 Shizuku：Android 11+ 系统禁止应用直接开关 WiFi/移动数据，" +
                "Shizuku 通过一次 adb 授权让本应用获得该能力（无需 Root）。\n\n" +
                "安装与激活步骤：\n" +
                "1. 点击下方「去安装」，直接安装应用内置的 Shizuku（无需下载）；\n" +
                "2. 手机开启「开发者选项 → USB 调试」，用数据线连接电脑；\n" +
                "3. 电脑上执行一次：\n" +
                "$cmdLine\n" +
                "4. 打开 Shizuku 应用，点击「启动」（开始）；\n" +
                "5. 在 Shizuku 的「已授权的应用」列表中找到「短信转发」，打开授权开关；\n" +
                "6. 返回本应用，保持此开关开启即可。"
        }
        AlertDialog.Builder(this)
            .setTitle(if (installed) "Shizuku 未激活" else "需要安装 Shizuku")
            .setMessage(message)
            .setPositiveButton(if (installed) "打开 Shizuku" else "去安装") { _, _ ->
                if (installed) {
                    launchShizukuApp()
                } else {
                    installShizukuFromAssets()
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    /**
     * 动态获取 Shizuku 13.x 的 adb 激活命令：
     * `adb shell <nativeLibraryDir>/libshizuku.so`（13.6 起不再使用 start.sh）。
     * 路径随安装实例变化，必须实时读取；Shizuku 未安装或库文件缺失时返回 null。
     */
    private fun shizukuAdbCommand(): String? {
        return runCatching {
            val info = packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            val lib = File(info.nativeLibraryDir, "libshizuku.so")
            if (lib.exists()) "adb shell ${lib.absolutePath}" else null
        }.getOrNull()
    }

    /**
     * 应用内直接安装内置的 Shizuku APK（assets/shizuku/shizuku.apk）：
     * 复制到 cacheDir → FileProvider 授权 → 系统安装器。全程无需联网下载。
     * 失败（如系统禁止安装未知应用）时回退到浏览器下载入口。
     */
    private fun installShizukuFromAssets() {
        try {
            val dest = File(cacheDir, "shizuku.apk")
            if (!dest.exists() || dest.length() == 0L) {
                assets.open("shizuku/shizuku.apk").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val uri = FileProvider.getUriForFile(this, "com.sms.forward.fileprovider", dest)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "应用内安装失败（可能未允许安装未知应用），将打开下载页兜底",
                Toast.LENGTH_LONG
            ).show()
            openShizukuDownload()
        }
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
    }.isSuccess

    private fun launchShizukuApp() {
        runCatching {
            startActivity(packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE))
        }.onFailure {
            Toast.makeText(this, "无法打开 Shizuku，请手动打开", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openShizukuDownload() {
        // 优先：装了酷安 → 酷安 App 内搜索 Shizuku（可直达下载页）
        val coolmarket = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_COOLMARKET_URI))
        if (coolmarket.resolveActivity(packageManager) != null) {
            startActivity(coolmarket)
            return
        }
        // 兜底：浏览器打开 GitHub 官方发布页
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL)))
        }.onFailure {
            Toast.makeText(this, "无法打开浏览器，请自行在酷安/GitHub 搜索 Shizuku 下载", Toast.LENGTH_SHORT).show()
        }
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
        loadingConfig = true
        binding.switchEnabled.isChecked = s.enabled
        binding.switchDryRun.isChecked = s.dryRun
        binding.inputSmtpHost.setText(s.host)
        binding.inputSmtpPort.setText(s.port.toString())
        binding.inputEncryption.setText(s.encryption, false)
        if (s.username.isNotBlank()) binding.inputUsername.setText(s.username)
        if (s.authCode.isNotBlank()) binding.inputAuthCode.setText(s.authCode)
        if (s.toEmail.isNotBlank()) binding.inputToEmail.setText(s.toEmail)
        binding.switchFullNumber.isChecked = s.showFullNumber
        binding.switchAutoNetwork.isChecked = s.autoNetwork
        // 每张卡的本机号码：优先读按卡槽存的值，旧单卡配置只迁移一次（迁移后清掉旧 key）
        val legacy = SecurePrefs.getString(this, SecurePrefs.KEY_OWNER_NUMBER).orEmpty()
        val slot0 = SecurePrefs.getString(this, SecurePrefs.KEY_OWNER_NUMBER_SLOT + 0).orEmpty()
        val slot1 = SecurePrefs.getString(this, SecurePrefs.KEY_OWNER_NUMBER_SLOT + 1).orEmpty()
        if (slot0.isBlank() && legacy.isNotBlank()) {
            SecurePrefs.putString(this, SecurePrefs.KEY_OWNER_NUMBER_SLOT + 0, legacy)
            SecurePrefs.remove(this, SecurePrefs.KEY_OWNER_NUMBER)
            binding.inputOwnerNumber1.setText(legacy)
        } else {
            binding.inputOwnerNumber1.setText(slot0)
        }
        binding.inputOwnerNumber2.setText(slot1)
        applySlotDetection()
        binding.inputInterval.setText((s.sendIntervalMs / 1000L).toString())
        loadingConfig = false

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

    /** 按实际插卡数量显示"每卡手机号"输入项，并把系统能读到的号码自动填入（可修改） */
    private fun applySlotDetection() {
        val slots = SimSlotDetector.detect(this)
        val slot0 = slots.firstOrNull { it.slot == 0 }
        val slot1 = slots.firstOrNull { it.slot == 1 }

        binding.tiOwnerNumber1.visibility = View.VISIBLE
        binding.tiOwnerNumber1.hint = slotHint(1, slot0)
        binding.tiOwnerNumber2.visibility = if (slots.isEmpty() || slot1 != null) View.VISIBLE else View.GONE
        binding.tiOwnerNumber2.hint = slotHint(2, slot1)

        // 输入框为空时自动填入系统读取到的号码；已有手填值不覆盖
        if (binding.inputOwnerNumber1.text.isNullOrBlank()) slot0?.phoneNumber?.let { binding.inputOwnerNumber1.setText(it) }
        if (binding.inputOwnerNumber2.text.isNullOrBlank()) slot1?.phoneNumber?.let { binding.inputOwnerNumber2.setText(it) }

        Log.i(
            TAG,
            "插卡检测: 有卡数量=${slots.size} " +
                slots.joinToString { "卡${it.slot + 1}(${it.carrierName ?: "运营商未知"},号码=${it.phoneNumber?.let { n -> "${n.take(3)}****${n.takeLast(4)}" } ?: "未读取"})" }
        )
    }

    private fun slotHint(index: Int, slot: SimSlotDetector.Slot?): String = buildString {
        append("卡${index}")
        slot?.carrierName?.let { append("·$it") }
        val read = slot?.phoneNumber
        if (read != null) append(" 系统读取 ${read.take(3)}****${read.takeLast(4)}") else append(" 未读取到")
        append("，可填/改")
    }

    private fun collect(): MailConfig.MailSettings? {        val host = binding.inputSmtpHost.text?.toString()?.trim().orEmpty()
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
            sendIntervalMs = intervalSec.coerceIn(1, 300) * 1000L,
            autoNetwork = binding.switchAutoNetwork.isChecked
        )
    }

    private fun saveConfig() {
        val settings = collect() ?: return
        MailConfig.save(this, settings)
        // v1.4.0：每张卡的本机号码（运营商未写 MSISDN 时手动指定，用于邮件标注前3后4）
        SecurePrefs.putString(
            this, SecurePrefs.KEY_OWNER_NUMBER_SLOT + 0,
            binding.inputOwnerNumber1.text?.toString()?.trim().orEmpty()
        )
        SecurePrefs.putString(
            this, SecurePrefs.KEY_OWNER_NUMBER_SLOT + 1,
            binding.inputOwnerNumber2.text?.toString()?.trim().orEmpty()
        )
        // 已改为按卡存储，清掉旧版单卡 key，避免清空后又被兜底写回
        SecurePrefs.remove(this, SecurePrefs.KEY_OWNER_NUMBER)

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
            Manifest.permission.READ_PHONE_STATE,
            // v1.4.0 设备状态：本机号码前3后4 + 邮件附带位置（可选，拒绝只影响附带信息）
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
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

    /** 网络自动开关所需权限：WiFi 开关（普通权限安装即授予）+ Android 13+ NEARBY_WIFI_DEVICES（运行时申请） */
    private fun requestNetworkPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            networkPermissionLauncher.launch(missing.toTypedArray())
        } else {
            Toast.makeText(this, "网络控制权限已具备", Toast.LENGTH_SHORT).show()
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
