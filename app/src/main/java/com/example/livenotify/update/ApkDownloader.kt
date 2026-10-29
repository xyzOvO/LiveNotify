package com.example.livenotify.update

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.livenotify.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.File

/**
 * 新版本 APK 下载。
 *
 * 用 OkHttp 直接下载而不是 DownloadManager：后者给的是 `content://downloads/...`，
 * 还要额外处理跨进程读权限授予，在 MIUI 上经常表现为「解析包失败」。
 * 自己下到应用私有目录，再经 FileProvider 交给安装器，成功率高得多。
 *
 * 文件名带版本号：装完没成功、用户返回再点一次时能直接复用已下好的包，不必重下。
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"

    private const val PREFIX = "LiveNotify-"

    /** 应用私有外部目录：Android 8+ 免存储权限；卸载 App 时系统一并清掉，不留垃圾 */
    private fun dir(context: Context): File {
        val d = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun fileFor(context: Context, version: String): File = File(dir(context), "$PREFIX$version.apk")

    /** 清掉历史版本的包；keep 传入本次要保留的文件 */
    fun clean(context: Context, keep: File? = null) {
        dir(context).listFiles()?.forEach { f ->
            val ours = f.name.startsWith(PREFIX) &&
                (f.name.endsWith(".apk") || f.name.endsWith(".part"))
            if (ours && f != keep) f.delete()
        }
    }

    /** 下载 APK；成功返回文件，失败返回 null。onProgress 回调 0~100 */
    suspend fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val finalFile = fileFor(context, version)

        // 上次已经下完（用户可能在系统安装页取消了），直接复用
        if (finalFile.exists() && finalFile.length() > 0) {
            onProgress(100)
            return@withContext finalFile
        }

        val tmp = File(dir(context), "$PREFIX$version.apk.part")
        tmp.delete()
        var ok = false
        try {
            val call = Http.client.newCall(Request.Builder().url(url).build())
            ok = call.execute().use { resp -> write(resp, tmp, onProgress) }
        } catch (t: Throwable) {
            Log.w(TAG, "download error", t)
        }
        if (!ok) {
            tmp.delete()
            return@withContext null
        }
        if (finalFile.exists()) finalFile.delete()
        if (tmp.renameTo(finalFile)) {
            clean(context, keep = finalFile)
            finalFile
        } else {
            tmp.delete()
            null
        }
    }

    /** 流式写入临时文件，写完返回 true */
    private fun write(resp: Response, dst: File, onProgress: (Int) -> Unit): Boolean {
        if (!resp.isSuccessful) {
            Log.w(TAG, "download failed: HTTP ${resp.code}")
            return false
        }
        val body = resp.body ?: return false
        val total = body.contentLength()
        val buf = ByteArray(32 * 1024)
        var done = 0L
        var lastEmit = -1
        val input = body.byteStream()
        val os = dst.outputStream()
        try {
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                os.write(buf, 0, r)
                done += r
                if (total <= 0L) continue
                val p = (done * 100 / total).toInt()
                if (p == lastEmit) continue
                lastEmit = p
                onProgress(p)
            }
        } finally {
            runCatching { os.close() }
            runCatching { input.close() }
        }
        // 服务端没给 Content-Length 时进度一直是 0，这里补一次完成态
        if (lastEmit != 100) onProgress(100)
        return true
    }
}
