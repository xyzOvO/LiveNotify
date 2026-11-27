package com.example.livenotify.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.livenotify.net.Http
import com.example.livenotify.util.DouyinCookieCapture

/**
 * 抖音登录页（WebView）→ 抓取登录态 Cookie。
 *
 * 安全边界：
 * - 只加载 https + 抖音系域名，其它导航一律拦掉，当浏览器乱逛。
 * - 不 addJavascriptInterface：页面 JS 没有任何原生回调通道。
 * - 关文件/内容访问、禁混合内容；Cookie 只由 WebView 自己管，抓到后交给
 *   [MainViewModel.setDouyinCookie]（内部用 AndroidKeyStore 加密落盘）。
 * - 抓取结果只显示「抓到 N 项 / 是否含登录态」，绝不把 Cookie 原文回显到界面或日志。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DouyinLoginScreen(
    vm: MainViewModel,
    onBack: () -> Unit
) {
    var captured by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("请在下方页面登录抖音，登录后点「抓取并保存」。") }

    fun capture() {
        val cm = CookieManager.getInstance()
        val chunks = DouyinCookieCapture.SOURCE_URLS.map { cm.getCookie(it) ?: "" }
        val merged = DouyinCookieCapture.mergeCookies(chunks)
        captured = merged
        status = if (DouyinCookieCapture.hasLoginSession(merged)) {
            "已抓到登录态（${DouyinCookieCapture.count(merged)} 项），点「抓取并保存」写入本机。"
        } else {
            "抓到 ${DouyinCookieCapture.count(merged)} 项，但没有登录态标志。请确认已在页面里登录，再重试。"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("抖音登录抓 Cookie") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { capture() }) { Text("抓取") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                "Cookie 等同于账号密码：仅加密保存在本机，不随备份导出、不上传。页面由抖音官方提供，如出现滑块/下载 App 拦截，按页面提示完成后再抓取。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        // 第三方 SSO 登录页要写 .douyin.com 的 Cookie，必须允许三方 Cookie
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.domStorageEnabled = true
                        settings.userAgentString = Http.UA_PC
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setGeolocationEnabled(false)
                        settings.savePassword = false
                        // 白色背景：页面加载前不再透出深色主题的黑屏
                        setBackgroundColor(Color.WHITE)
                        CookieManager.getInstance().apply { setAcceptCookie(true) }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                url: String
                            ): Boolean = !isDouyinUrl(url)
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                update = { /* 配置一次即可，页面状态由 WebView 自己保留 */ }
            )
            TextButton(
                onClick = {
                    if (captured.isNotBlank() && DouyinCookieCapture.hasLoginSession(captured)) {
                        vm.setDouyinCookie(captured)
                        onBack()
                    } else {
                        status = "还没抓到登录态，请先登录再点「抓取」。"
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text("抓取并保存")
            }
        }
    }
}

/** 允许的导航域名：抖音主站/直播/SSO，防止 WebView 被当成通用浏览器 */
private fun isDouyinUrl(url: String): Boolean {
    val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull() ?: return false
    if (!url.startsWith("https://")) return false
    return listOf(".douyin.com", ".snssdk.com", ".iesdouyin.com", ".amemv.com")
        .any { host.endsWith(it) } || host == "douyin.com"
}

/** 官方网页版入口：未登录时会自动弹登录框，比 live.douyin.com 的 App 壳页更适合做登录首屏 */
private const val LOGIN_URL = "https://www.douyin.com/"
