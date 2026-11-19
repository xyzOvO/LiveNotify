package com.example.livenotify.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.livenotify.BuildConfig
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 运行时授权结果是一次性回调，用自增计数通知主页面重新读取权限状态 */
    private var permissionRevision by mutableStateOf(0)

    /**
     * 从分享菜单带进来的文案。消费后置空，避免横竖屏重建时反复弹添加框。
     * 这里只搬运文本，解析与落库仍在用户点「添加」之后发生。
     */
    private var pendingShareText by mutableStateOf<String?>(null)

    /** 每收到一次新分享就自增；驱动 Compose 把页面切回主页并重新触发预填 */
    private var shareRevision by mutableStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionRevision++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只在冷启动时读一次；savedInstanceState 非空说明是重建，别把上次分享再弹一遍
        if (savedInstanceState == null) {
            pendingShareText = intent?.getStringExtra(ShareReceiveActivity.EXTRA_SHARED_TEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // adb 调试入口：
        // adb shell am start -n com.example.livenotify/.ui.MainActivity \
        //   -e auto_add "https://live.bilibili.com/21452505" [-e platform bilibili]
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("auto_add")?.takeIf { it.isNotBlank() }?.let { raw ->
                val vm = ViewModelProvider(this)[MainViewModel::class.java]
                val plat = intent.getStringExtra("platform")
                lifecycleScope.launch {
                    // 调试入口保持一步到位：识别通过就直接落库，方便脚本化验证
                    val resolved = vm.resolve(raw, plat)
                    val r = if (resolved is MainViewModel.AddResult.Ready) {
                        vm.confirmAdd(resolved.entity)
                    } else resolved
                    Log.i("LiveNotifyDebug", "auto_add [$raw] -> $r")
                }
            }
        }
        setContent {
            LiveNotifyTheme {
                // 导航栈：二级页返回上一级，而不是一律回首页。
                // 例如 设置 → 平台诊断，返回应该回到设置；首页 → 历史，返回回首页。
                // 只存 Screen 名（String 列表能被 rememberSaveable 序列化），不存对象。
                var backStack by rememberSaveable {
                    mutableStateOf(listOf(Screen.Main.name))
                }
                val screen = Screen.entries.firstOrNull { it.name == backStack.last() } ?: Screen.Main
                // 同一个页面不重复入栈，否则连点两下要按两次返回才退出
                fun go(next: Screen) {
                    if (next.name != backStack.last()) backStack = backStack + next.name
                }
                // 回上一级；栈底就是首页，不动它（交给系统默认的退出行为）
                fun back() {
                    if (backStack.size > 1) backStack = backStack.dropLast(1)
                }
                // B 站数据页要带上是「看哪个主播」，用 0 表示由页面自己挑列表里第一个
                var statsUid by rememberSaveable { mutableStateOf(0L) }
                // 拦截系统返回与边缘侧滑手势：非首页先回上一级，而不是直接退出到桌面。
                // 首页不拦截，返回键/侧滑保持「退出 App」的默认行为。
                // 分享进来时直接清栈回首页（用户可能正停在设置页/数据页），再交给 MainScreen 预填
                LaunchedEffect(shareRevision) {
                    if (shareRevision > 0 && backStack != listOf(Screen.Main.name)) {
                        backStack = listOf(Screen.Main.name)
                    }
                }
                BackHandler(enabled = backStack.size > 1) { back() }
                // 每个页面各自的「可保存状态」（列表滚动位置、搜索框内容、筛选条件）。
                // 没有它，从设置进诊断再返回会跳回顶部，主页的搜索条件也会被清空。
                // 注意只保留 rememberSaveable 的状态，普通 remember（比如弹窗开关）不保留，
                // 所以返回时不会自带一个没关掉的对话框。
                val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                when (screen) {
                    Screen.Main -> pageStates.SaveableStateProvider(Screen.Main.name) {
                        MainScreen(
                            vm = androidx.lifecycle.viewmodel.compose.viewModel(),
                            onOpenSettings = { go(Screen.Settings) },
                            permissionRevision = permissionRevision,
                            onOpenStats = { uid ->
                                statsUid = uid
                                go(Screen.Stats)
                            },
                            onOpenHistory = { go(Screen.History) },
                            onOpenGroups = { go(Screen.GroupManage) },
                            sharedText = pendingShareText,
                            onConsumeSharedText = { pendingShareText = null }
                        )
                    }
                    Screen.Settings -> pageStates.SaveableStateProvider(Screen.Settings.name) {
                        SettingsScreen(
                            vm = androidx.lifecycle.viewmodel.compose.viewModel(),
                            onBack = { back() },
                            onOpenStats = {
                                statsUid = 0L
                                go(Screen.Stats)
                            },
                            onOpenDiagnostic = { go(Screen.Diagnostic) },
                            onOpenDetectDetail = { go(Screen.DetectDetail) }
                        )
                    }
                    Screen.Stats -> pageStates.SaveableStateProvider(Screen.Stats.name) {
                        StatsScreen(
                            vm = androidx.lifecycle.viewmodel.compose.viewModel<StatsViewModel>(),
                            initialUid = statsUid,
                            onBack = { back() }
                        )
                    }
                    Screen.History -> pageStates.SaveableStateProvider(Screen.History.name) {
                        HistoryScreen(
                            vm = androidx.lifecycle.viewmodel.compose.viewModel<HistoryViewModel>(),
                            onBack = { back() }
                        )
                    }
                    Screen.GroupManage -> pageStates.SaveableStateProvider(Screen.GroupManage.name) {
                        GroupManageScreen(
                            vm = androidx.lifecycle.viewmodel.compose.viewModel<MainViewModel>(),
                            onBack = { back() }
                        )
                    }
                    Screen.Diagnostic -> pageStates.SaveableStateProvider(Screen.Diagnostic.name) {
                        DiagnosticScreen(
                            vm = androidx.lifecycle.viewmodel.compose.viewModel<MainViewModel>(),
                            onBack = { back() }
                        )
                    }
                    Screen.DetectDetail -> pageStates.SaveableStateProvider(Screen.DetectDetail.name) {
                        DetectDetailScreen(
                            vm = androidx.lifecycle.viewmodel.compose.viewModel<MainViewModel>(),
                            onBack = { back() }
                        )
                    }
                }
            }
        }
    }
    /** 已经开着 App 时再分享一次：复用同一个实例，把新文案换上去 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.getStringExtra(ShareReceiveActivity.EXTRA_SHARED_TEXT)
        if (!incoming.isNullOrBlank()) {
            // 切回主页面，否则停留在设置/数据页时用户看不到预填结果
            pendingShareText = incoming
            shareRevision++
        }
    }
}

enum class Screen { Main, Settings, Stats, History, GroupManage, Diagnostic, DetectDetail }
