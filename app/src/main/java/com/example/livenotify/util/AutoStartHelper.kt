package com.example.livenotify.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM 的「自启动 / 后台运行」引导。
 *
 * 系统层面没有统一入口，各家都是私有 Activity，且随版本改名。
 * 这里的策略是：按厂商逐个试，失败就退到应用详情页——那里一定能找到「自启动」「省电策略」入口。
 * 所以 [openAutoStartSettings] 返回 false 不代表失败，只代表跳的是通用页面。
 */
object AutoStartHelper {

    /** 是否已被排除电池优化（true = 不受后台限制，最理想） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 弹系统「是否允许后台运行」对话框（已忽略时不重复弹） */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return false
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 打开厂商的自启动管理页。返回 true 表示命中了厂商专属页，false 表示退到了应用详情页。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        for (i in vendorIntents()) {
            if (start(context, i)) return true
        }
        openAppDetails(context)
        return false
    }

    /** 应用详情页：所有 ROM 都有，自启动 / 省电策略的入口一般都在里面 */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** 按厂商常见入口排好序；命中第一个能打开的即可 */
    private fun vendorIntents(): List<Intent> {
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        val miui = Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        val huawei = component(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
        val honor = component(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
        val oppo = component(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        )
        val oppoOld = component(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        )
        val vivo = component(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
        val vivoOld = component(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        )
        val samsung = component(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
        val meizu = component(
            "com.meizu.safe",
            "com.meizu.safe.permission.SmartBGActivity"
        )

        return when {
            maker.contains("xiaomi") || maker.contains("redmi") ->
                listOf(miui, component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            maker.contains("huawei") -> listOf(huawei)
            maker.contains("honor") -> listOf(honor, huawei)
            maker.contains("oppo") || maker.contains("realme") -> listOf(oppo, oppoOld)
            maker.contains("vivo") || maker.contains("iqoo") -> listOf(vivo, vivoOld)
            maker.contains("samsung") -> listOf(samsung)
            maker.contains("meizu") -> listOf(meizu)
            // 其他品牌：常见入口都试一遍，反正失败会退到应用详情页
            else -> listOf(miui, huawei, honor, oppo, vivo)
        }
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))
}
