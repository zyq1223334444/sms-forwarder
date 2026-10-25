package com.sms.forward.ui

import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sms.forward.databinding.ActivityReadmeBinding
import com.sms.forward.util.MarkdownRenderer

/**
 * 离线展示 assets 内置的 README（Markdown）。
 * 入参：EXTRA_ASSET = assets 中的 markdown 路径，EXTRA_TITLE = 页面标题。
 */
class ReadmeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReadmeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadmeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val asset = intent.getStringExtra(EXTRA_ASSET) ?: "readme/README.md"
        title = intent.getStringExtra(EXTRA_TITLE) ?: "README"

        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        try {
            val md = assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val html = MarkdownRenderer.render(md, dark)
            binding.webReadme.settings.apply {
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            binding.webReadme.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
        } catch (e: Exception) {
            Toast.makeText(this, "说明文档加载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::binding.isInitialized && binding.webReadme.canGoBack()) {
            binding.webReadme.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_ASSET = "asset"
        const val EXTRA_TITLE = "title"
    }
}
