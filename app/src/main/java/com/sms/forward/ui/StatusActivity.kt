package com.sms.forward.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sms.forward.databinding.ActivityStatusBinding
import com.sms.forward.databinding.ItemSmsBinding
import com.sms.forward.data.AppDatabase
import com.sms.forward.data.DedupKey
import com.sms.forward.data.SmsEntity
import com.sms.forward.data.SmsStatus
import com.sms.forward.mail.MailConfig
import com.sms.forward.util.SecurePrefs
import com.sms.forward.work.ReconcileWorker
import com.sms.forward.work.SendQueueWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 状态/日志页（M7 完成）：
 * 最近短信记录列表、失败项手动重试、清理干跑历史、权限缺失提示。
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusBinding
    private val adapter = SmsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerSms.layoutManager = LinearLayoutManager(this)
        binding.recyclerSms.adapter = adapter

        binding.btnRetryFailed.setOnClickListener { retryFailed() }
        binding.btnClearDryrun.setOnClickListener { clearDryRun() }
        binding.btnSimulate.setOnClickListener { simulateSms() }
        binding.btnScanNow.setOnClickListener { scanNow() }

        checkPermissionHint()
        observeRecords()
    }

    /** 立即触发一次补偿扫描（兜底：把系统短信库中的新短信补入库）。
     * 重置基线让扫描按"近30分钟内新短信"评估；uniqueKey 去重保证重复扫描不重发。 */
    private fun scanNow() {
        SecurePrefs.putLong(this, MailConfig.KEY_RECONCILE_BASELINE_ID, -1L)
        ReconcileWorker.runOnce(this)
        Toast.makeText(this, "已触发扫描，近30分钟短信将补入库并发送", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissionHint() {
        val missing = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            binding.tvPermissionHint.text = "警告：以下权限未授予，短信接收/补偿可能不完整：${missing.joinToString("、") { it.substringAfterLast('.') }}"
            binding.tvPermissionHint.visibility = android.view.View.VISIBLE
        }
    }

    private fun observeRecords() {
        val dao = AppDatabase.get(this).smsDao()
        lifecycleScope.launch {
            dao.observeRecent(200).collect { list ->
                adapter.submit(list)
            }
        }
    }

    private fun retryFailed() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(this@StatusActivity).smsDao()
            val count = dao.requeueAllFailed()
            if (count > 0) SendQueueWorker.enqueue(this@StatusActivity)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@StatusActivity, if (count > 0) "已重新入队 $count 条失败记录" else "没有可重试的失败记录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearDryRun() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(this@StatusActivity).smsDao()
            val count = dao.clearDryRun()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@StatusActivity, "已清理 $count 条干跑记录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 模拟测试短信：不依赖真实短信广播，直接插入一条 PENDING 记录并入队。
     * 用于把"短信监听"与"邮件发送"两个环节拆开验证——
     * 若此条能收到邮件，说明邮箱/授权码/发送链路正常，问题在系统短信广播拦截。
     */
    private fun simulateSms() {
        val settings = MailConfig.load(this)
        if (!settings.enabled) {
            Toast.makeText(this, "请先在设置中启用短信转发", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
            val sender = "10000"
            val body = "【测试】这是一条由应用内「模拟测试短信」生成的测试短信，用于验证邮件转发链路。生成时间：$timeText"
            val key = DedupKey.compute(sender, now, null, body, 1)
            val dao = AppDatabase.get(this@StatusActivity).smsDao()
            val entity = SmsEntity(
                uniqueKey = key,
                sender = DedupKey.normalizeSender(sender),
                senderRaw = sender,
                body = body,
                receivedAt = now,
                status = SmsStatus.PENDING
            )
            val rowId = dao.insert(entity)
            if (rowId != -1L) SendQueueWorker.enqueue(this@StatusActivity)
            withContext(Dispatchers.Main) {
                val msg = if (settings.dryRun) {
                    "已生成测试短信（干跑模式，不会真实发送，状态应为干跑）"
                } else {
                    "已生成测试短信，正在发送（约 4 秒/封），请稍后查看邮箱与列表状态"
                }
                Toast.makeText(this@StatusActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** 简单列表适配器（按需刷新整表） */
private class SmsAdapter : RecyclerView.Adapter<SmsAdapter.VH>() {

    private val items = mutableListOf<SmsEntity>()

    fun submit(list: List<SmsEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemSmsBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSmsBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvSender.text = item.senderRaw
        holder.binding.tvBody.text = item.body
        holder.binding.tvStatus.text = statusLabel(item)
        holder.binding.tvStatus.setTextColor(statusColor(holder.binding.tvStatus, item.status))
        holder.binding.tvTime.text = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(item.receivedAt))
        val err = item.lastError
        if (!err.isNullOrBlank()) {
            holder.binding.tvError.text = "错误详情：$err"
            holder.binding.tvError.visibility = android.view.View.VISIBLE
        } else {
            holder.binding.tvError.visibility = android.view.View.GONE
        }
    }

    private fun statusLabel(item: SmsEntity): String = when (item.status) {
        SmsStatus.PENDING -> "待发送"
        SmsStatus.SENDING -> "发送中"
        SmsStatus.DRY_RUN -> "干跑"
        SmsStatus.SENT -> "已发送"
        SmsStatus.FAILED_RETRYABLE -> "失败(重试${item.retryCount}次)"
        SmsStatus.FAILED_PERMANENT -> "失败(永久)"
    }

    private fun statusColor(view: TextView, status: SmsStatus): Int {
        val ctx = view.context
        return when (status) {
            SmsStatus.SENT -> ctx.getColor(android.R.color.holo_green_dark)
            SmsStatus.DRY_RUN -> ctx.getColor(android.R.color.holo_blue_dark)
            SmsStatus.FAILED_RETRYABLE, SmsStatus.FAILED_PERMANENT -> ctx.getColor(android.R.color.holo_red_dark)
            else -> ctx.getColor(android.R.color.darker_gray)
        }
    }
}
