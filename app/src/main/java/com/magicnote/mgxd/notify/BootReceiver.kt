package com.magicnote.mgxd.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.magicnote.mgxd.LinxiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机/升级广播接收器：恢复所有未完成的提醒调度
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        scope.launch {
            try {
                val app = context.applicationContext as LinxiApp
                ReminderScheduler.rescheduleAll(context, app.container.repository)
                // 纯净模式：不拉起后台守护
                if (!app.container.repository.pureMode.first()) {
                    com.magicnote.mgxd.service.KeepAliveService.start(context)
                }
            } finally {
                result.finish()
            }
        }
    }
}