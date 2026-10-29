package com.example.livenotify.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * 调起系统安装界面。
 *
 * 说明：普通应用做不到「静默安装」——那需要系统签名或 ROOT。
 * 这里能做到的极限是：后台下载完 APK 后，直接把系统的「安装确认」页推到前台，
 * 用户只需点一次「安装」（MIUI 上还可能有一层安全扫描确认），之后 App 自动重启，数据不丢。
 *
 * 相比让用户自己打开浏览器下载再找文件，省掉了最烦的几步。
 */
object ApkInstaller {

    /** Android 8 起需要用户先授权「允许来自此来源的应用」，否则安装页会直接被拒 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        i.data = Uri.parse("package:${context.packageName}")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    /**
     * 判断下载的包是否与本应用同签名。
     *
     * 不同签名的包系统会直接拒绝覆盖安装，只弹一句「应用未安装」，用户完全看不懂。
     * 所以在拉起安装页之前先自己比对一次，给出能看懂的提示。
     * 任何解析失败都返回 true（宁可让系统去判，也不要误拦）。
     */
    @Suppress("DEPRECATION")
    fun signatureMatches(context: Context, file: File): Boolean {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

            val pm = context.packageManager
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            val self = pm.getPackageInfo(context.packageName, flags)

            val incoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                archive?.signingInfo?.apkContentsSigners
            else archive?.signatures
            val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                self.signingInfo?.apkContentsSigners
            else self.signatures

            if (incoming.isNullOrEmpty() || current.isNullOrEmpty()) return true
            val a = incoming.first().toByteArray()
            val b = current.first().toByteArray()
            if (a.isEmpty() || b.isEmpty()) true else digest(a).contentEquals(digest(b))
        } catch (t: Throwable) {
            true
        }
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** 拉起系统安装页；返回 false 表示没有可用的安装器或 URI 授权失败 */
    fun install(context: Context, file: File): Boolean {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
        } catch (t: Throwable) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 有些 ROM 只给「被选中处理 intent 的那个包」授予读权限，
        // 这里对所有候选包都放行，避免出现「解析包失败 / 应用未安装」。
        runCatching {
            val list = context.packageManager.queryIntentActivities(intent, 0)
            for (r in list) {
                val pkg = r.activityInfo?.packageName
                if (!pkg.isNullOrBlank()) {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
