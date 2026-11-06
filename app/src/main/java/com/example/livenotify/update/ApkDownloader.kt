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

    /** 外部存储不可用时的内部下载目录，必须与 res/xml/file_paths.xml 的 files-path 一致 */
    private const val INTERNAL_DIR = "updates"

    /**
     * 应用私有外部目录：Android 8+ 免存储权限；卸载 App 时系统一并清掉，不留垃圾。
     * 外部存储不可用（挂载异常 / 部分定制 ROM）时退回内部目录——必须放在 file_paths.xml
     * 声明过的子目录里，否则 FileProvider.getUriForFile 会直接抛异常，安装页打不开。
     */
    private fun dir(context: Context): File {
        val d = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, INTERNAL_DIR)
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

    /** 下载结果：把「网络失败」和「文件校验失败」分开，界面才能给对提示 */
    sealed interface DownloadResult {
        data class Ok(val file: File) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    private const val HASH_FAIL = "安装包校验未通过（文件可能下载不完整或已被替换），已删除，请重试"

    /**
     * 下载 APK。expectedSha256 非空时，新下载的包和上次留下的包都要核对文件 hash；
     * 不符一律删掉——坏包留着会让下次点「重试」继续复用同一个文件。
     */
    suspend fun download(
        context: Context,
        url: String,
        version: String,
        expectedSha256: String? = null,
        onProgress: (Int) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val finalFile = fileFor(context, version)

        // 上次已经下完（用户可能在系统安装页取消了），校验通过才复用
        if (finalFile.exists() && finalFile.length() > 0) {
            if (ApkHash.matches(finalFile, expectedSha256)) {
                onProgress(100)
                return@withContext DownloadResult.Ok(finalFile)
            }
            finalFile.delete()
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
            return@withContext DownloadResult.Failed("下载失败，请检查网络后重试")
        }
        if (!ApkHash.matches(tmp, expectedSha256)) {
            tmp.delete()
            return@withContext DownloadResult.Failed(HASH_FAIL)
        }
        if (finalFile.exists()) finalFile.delete()
        if (tmp.renameTo(finalFile)) {
            clean(context, keep = finalFile)
            DownloadResult.Ok(finalFile)
        } else {
            tmp.delete()
            DownloadResult.Failed("下载失败，请检查网络后重试")
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
