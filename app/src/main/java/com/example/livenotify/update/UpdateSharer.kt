package com.example.livenotify.update

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 把「最新版本的公开下载地址」分享出去。
 *
 * 只用系统分享面板（ACTION_SEND + text/plain），不集成任何第三方 SDK：
 * 装了什么应用由系统列，用户自己选，分享完回到 App 状态不变。
 *
 * 地址一律取 [ReleaseInfo.apkUrl]——那是服务器（GitHub Releases）返回的
 * HTTPS 公开直链，既能下载也能直接给朋友。**绝不用本地文件路径或
 * FileProvider 临时 URI 代替**：那种地址换个设备就打不开了。
 */
object UpdateSharer {

    private const val TAG = "UpdateSharer"

    /** 分享文本里更新日志的上限：太长会撑爆微信/短信的输入框，取前面几行足够说明问题 */
    private const val MAX_NOTES = 600

    /**
     * 生成分享文本。纯函数，便于单测。
     *
     * 返回 null 表示**地址不可用**（空 / 不是 HTTPS），调用方必须禁止分享，
     * 不能退而求其次分享一个打不开的地址。
     *
     * 更新日志为空时整段省略，不留「更新内容：」这种空标题。
     */
    internal fun shareText(
        info: ReleaseInfo,
        appName: String,
        maxNotes: Int = MAX_NOTES
    ): String? {
        val url = info.apkUrl.trim()
        if (!isHttps(url)) return null

        val notes = ReleaseNotes.format(info.notes, maxNotes).trim()
        return buildString {
            append(appName).append(" v").append(info.versionName)
            if (notes.isNotEmpty()) {
                append("\n\n更新内容：\n").append(notes)
            }
            // 空行分隔：很多聊天软件会把紧挨着的长 URL 一起吞进链接识别
            append("\n\n下载地址：\n").append(url)
        }
    }

    /** 只认 HTTPS：http 明文地址和空串都不允许用来分享 */
    internal fun isHttps(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && url.length > "https://".length

    /**
     * 拉起系统分享面板。
     * @return false 表示设备上没有能接收纯文本的应用（极少见），调用方给「复制链接」兜底
     */
    fun share(context: Context, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        // 从 Application/Service 上下文启动时必须有 NEW_TASK
        val chooser = Intent.createChooser(send, "分享下载链接").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "no activity to handle share", t)
            false
        }
    }

    /** 复制到系统剪贴板。返回 false 表示系统剪贴板不可用（理论上不会发生） */
    fun copy(context: Context, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("下载链接", text))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "copy to clipboard failed", t)
        false
    }
}
