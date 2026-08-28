package com.magicnote.mgxd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.magicnote.mgxd.notify.NotificationHelper
import com.magicnote.mgxd.notify.ReminderScheduler
import com.magicnote.mgxd.ui.navigation.AppNav
import com.magicnote.mgxd.ui.theme.LinxiTheme
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

        setContent {
            LinxiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
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
            val app = applicationContext as LinxiApp
            val repo = app.container.repository
            ReminderScheduler.rescheduleAll(applicationContext, repo)
            // 纯净模式：不启动后台守护
            if (!repo.pureMode.first()) {
                com.magicnote.mgxd.service.KeepAliveService.start(this@MainActivity)
            }
        }
    }
}