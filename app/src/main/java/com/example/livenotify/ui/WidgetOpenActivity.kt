package com.example.livenotify.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 集合小组件条目点击的中转页。
 *
 * RemoteViews 集合的子项只能用「mutable template + fillIn」响应点击，
 * 而 targetSdk 35 不允许 mutable + 隐式 Intent——所以 template 指向这个显式 Activity，
 * Factory 用 fillIn 把直播间链接塞进 data，这里读出来转给系统。
 * 不显示界面（主题在 Manifest 里设为透明），转完立刻 finish。
 */
class WidgetOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.data?.toString().orEmpty()
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        finish()
    }
}
