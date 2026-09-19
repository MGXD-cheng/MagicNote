package com.magicnote.mgxd

import com.magicnote.mgxd.util.MgxdIntentHolder
import android.widget.Toast
import android.content.Intent
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.magicnote.mgxd.notify.NotificationHelper
import com.magicnote.mgxd.notify.ReminderScheduler
import com.magicnote.mgxd.ui.navigation.AppNav
import com.magicnote.mgxd.ui.theme.MGTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
class MainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户选择后无需额外处理 */ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.ensureChannels(this)
        requestNotificationPermissionIfNeeded()
        // 恢复闹钟调度 + 按纯净模式决定是否启动后台守护（见 scheduleReminders）
        scheduleReminders()
        // 从文件管理器 / 浏览器打开 .mgxd 备份文件时直接进入导入流程
        handleViewIntent(intent)
        // 打开 App 时刷新桌面小组件（数据可能在别处发生变化）
        com.magicnote.mgxd.widget.TodoWidgetProvider.refreshAll(this)
        com.magicnote.mgxd.widget.CountdownWidgetProvider.refreshAll(this)
        com.magicnote.mgxd.widget.QuickNoteWidgetProvider.refreshAll(this)

        setContent {
            // 外观主题：设置里的 跟随系统/浅色/深色
            val repo = (application as MGApp).container.repository
            val themeMode by repo.themeMode.collectAsState(initial = "system")
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            MGTheme(darkTheme = darkTheme) {
                // 手动切深色时同步状态栏图标深浅，避免图标看不清
                val view = LocalView.current
                val context = LocalContext.current
                DisposableEffect(darkTheme) {
                    val activity = context as? Activity
                    if (activity != null) {
                        WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars = !darkTheme
                    }
                    onDispose { }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
    /** .mgxd 文件关联：系统以 VIEW 打开 .mgxd 文件时读取内容并投递给 UI */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        activityScope.launch {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val bytes = ins.readBytes()
                    if (bytes.size > 32 * 1024 * 1024) null else String(bytes, Charsets.UTF_8)
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "无法读取该文件，可能不是 .mgxd 备份", Toast.LENGTH_LONG).show()
                }
            } else {
                MgxdIntentHolder.post(text)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun scheduleReminders() {
        activityScope.launch {
            val app = applicationContext as MGApp
            val repo = app.container.repository
            ReminderScheduler.rescheduleAll(applicationContext, repo)
            // 纯净模式：不启动后台守护
            if (!repo.pureMode.first()) {
                com.magicnote.mgxd.service.KeepAliveService.start(this@MainActivity)
            }
        }
    }
}