package com.sms.forward.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sms.forward.databinding.ActivityAboutBinding

/**
 * 关于页：显示应用版本号，提供中英文 README 离线查看入口与 GitHub 项目主页链接。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = "关于"

        val version = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            }
            "版本 ${pi.versionName}（$code）"
        }.getOrDefault("版本未知")
        binding.tvVersion.text = version

        binding.btnReadmeZh.setOnClickListener {
            startActivity(Intent(this, ReadmeActivity::class.java).apply {
                putExtra(ReadmeActivity.EXTRA_ASSET, "readme/README.zh-CN.md")
                putExtra(ReadmeActivity.EXTRA_TITLE, "使用说明")
            })
        }
        binding.btnReadmeEn.setOnClickListener {
            startActivity(Intent(this, ReadmeActivity::class.java).apply {
                putExtra(ReadmeActivity.EXTRA_ASSET, "readme/README.md")
                putExtra(ReadmeActivity.EXTRA_TITLE, "README")
            })
        }
        binding.btnGithub.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val GITHUB_URL = "https://github.com/zyq1223334444/sms-forwarder"
    }
}
