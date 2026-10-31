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

    /** 安装包自检结果。任一项不通过都在拉起安装页之前拦下，给出能看懂的原因 */
    sealed interface ApkCheck {
        data class Ok(val versionCode: Long) : ApkCheck
        data class Rejected(val reason: String) : ApkCheck
    }

    /**
     * 拉起安装页之前先自检：包名、签名、版本。
     *
     * 只比签名是不够的——错包 / 下载被截断 / 附件挂错都会一路走到系统安装器，
     * 用户只会看到「应用未安装」这种看不懂的提示。这里全部前置判断，且**解析失败即拒绝**
     * （以前 fail-open，坏包反而放行）。
     *
     * @param expectedVersionCode Release 公告的版本号；>0 时安装包不得低于它
     */
    @Suppress("DEPRECATION")
    fun check(context: Context, file: File, expectedVersionCode: Long = 0L): ApkCheck {
        if (!file.exists() || file.length() <= 0L) {
            return ApkCheck.Rejected("安装包不存在或为空，请重新下载")
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager

        val archive = try {
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        } catch (t: Throwable) {
            null
        } ?: return ApkCheck.Rejected("安装包无法解析，可能下载不完整，请重新下载")

        val self = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (t: Throwable) {
            null
        } ?: return ApkCheck.Rejected("无法读取本应用的签名信息，已中止安装")

        if (archive.packageName != context.packageName) {
            return ApkCheck.Rejected("下载到的不是本应用的安装包（包名 ${archive.packageName}）")
        }

        val incoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            archive.signingInfo?.apkContentsSigners
        else archive.signatures
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            self.signingInfo?.apkContentsSigners
        else self.signatures

        if (incoming.isNullOrEmpty() || current.isNullOrEmpty()) {
            return ApkCheck.Rejected("安装包或本应用缺少签名信息，已中止安装（可到 Releases 页手动下载）")
        }
        if (!digest(incoming.first().toByteArray()).contentEquals(digest(current.first().toByteArray()))) {
            return ApkCheck.Rejected(
                "安装包签名与当前应用不一致，系统会拒绝覆盖安装。" +
                    "可到 Releases 页手动下载，或卸载本应用后重装（关注列表会丢失）。"
            )
        }

        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            archive.longVersionCode else archive.versionCode.toLong()
        val selfCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            self.longVersionCode else self.versionCode.toLong()

        // 公告版本比包内版本高，说明下错了附件（Releases 里可能挂了别的包）
        if (expectedVersionCode > 0L && newCode < expectedVersionCode) {
            return ApkCheck.Rejected("安装包版本（$newCode）低于公告版本（$expectedVersionCode），请重新下载")
        }
        // 同版本或降级安装没有意义，系统也会拦；提前说清楚
        if (newCode <= selfCode) {
            return ApkCheck.Rejected("安装包版本（$newCode）未高于当前版本（$selfCode），无需安装")
        }

        return ApkCheck.Ok(newCode)
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
