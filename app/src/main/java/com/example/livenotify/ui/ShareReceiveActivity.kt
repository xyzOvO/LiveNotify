package com.example.livenotify.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 系统分享菜单的中转页：把别处分享出来的文字交给主页面预填到「添加关注」框。
 *
 * 刻意不在这里解析、更不在这里落库——分享过来的可能是任意文本（含手机号、
 * 验证码之类），直接写数据库既越权又容易加错人。页面只负责搬运，
 * 识别结果和「添加」按钮都留在原有的添加对话框里，由用户自己确认。
 *
 * 用独立 Activity 而不是给 MainActivity 加 launchMode：主页面从通知、桌面、
 * 设置页返回的行为已经调过，改启动模式风险更大。
 */
class ShareReceiveActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText(intent)
        // android:theme=Theme.NoDisplay 要求不 setContentView，且必须尽快 finish
        val forward = Intent(this, MainActivity::class.java).apply {
            // 已经开着 App 时复用同一个主页实例，避免分享一次叠一层 Activity
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (text != null) putExtra(EXTRA_SHARED_TEXT, text)
        }
        startActivity(forward)
        finish()
    }

    private fun extractText(src: Intent?): String? {
        if (src?.action != Intent.ACTION_SEND) return null
        val raw = src.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: src.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: return null
        // 超长文本既没意义也会撑爆 Intent，截断后交给下游解析器
        return raw.trim().take(MAX_LEN).takeIf { it.isNotBlank() }
    }

    companion object {
        /** 与 MainActivity 约定的参数名 */
        const val EXTRA_SHARED_TEXT = "shared_text"
        private const val MAX_LEN = 2000
    }
}
