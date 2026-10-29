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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                // 拦截系统返回与边缘侧滑手势：在设置页时先回主页面，而不是直接退出到桌面。
                // 主页面不拦截，返回键/侧滑保持「退出 App」的默认行为。
                BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Main }
                when (screen) {
                    Screen.Main -> MainScreen(
                        vm = androidx.lifecycle.viewmodel.compose.viewModel(),
                        onOpenSettings = { screen = Screen.Settings }
                    )
                    Screen.Settings -> SettingsScreen(
                        vm = androidx.lifecycle.viewmodel.compose.viewModel(),
                        onBack = { screen = Screen.Main }
                    )
                }
            }
        }
    }
}

enum class Screen { Main, Settings }
