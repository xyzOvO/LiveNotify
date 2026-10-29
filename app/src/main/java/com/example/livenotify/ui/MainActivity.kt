package com.example.livenotify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionRevision++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    val r = vm.add(raw, plat)
                    Log.i("LiveNotifyDebug", "auto_add [$raw] -> $r")
                }
            }
        }
        setContent {
            LiveNotifyTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Main) }
                // B 站数据页要带上是「看哪个主播」，用 0 表示由页面自己挑列表里第一个
                var statsUid by rememberSaveable { mutableStateOf(0L) }
                // 拦截系统返回与边缘侧滑手势：二级页面先回主页面，而不是直接退出到桌面。
                // 主页面不拦截，返回键/侧滑保持「退出 App」的默认行为。
                BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }
                when (screen) {
                    Screen.Main -> MainScreen(
                        vm = androidx.lifecycle.viewmodel.compose.viewModel(),
                        onOpenSettings = { screen = Screen.Settings },
                        permissionRevision = permissionRevision,
                        onOpenStats = { uid ->
                            statsUid = uid
                            screen = Screen.Stats
                        }
                    )
                    Screen.Settings -> SettingsScreen(
                        vm = androidx.lifecycle.viewmodel.compose.viewModel(),
                        onBack = { screen = Screen.Main },
                        onOpenStats = {
                            statsUid = 0L
                            screen = Screen.Stats
                        }
                    )
                    Screen.Stats -> StatsScreen(
                        vm = androidx.lifecycle.viewmodel.compose.viewModel<StatsViewModel>(),
                        initialUid = statsUid,
                        onBack = { screen = Screen.Main }
                    )
                }
            }
        }
    }
}

enum class Screen { Main, Settings, Stats }
