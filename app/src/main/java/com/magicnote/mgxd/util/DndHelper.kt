package com.magicnote.mgxd.util

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 系统「勿扰」（Do Not Disturb）控制助手。
 *
 * 专注模式进入时自动开启勿扰、退出时恢复原状态。
 * 需要用户授予「通知访问权限」（ACCESS_NOTIFICATION_POLICY），
 * 未授权时所有方法都安全降级（返回 false / null），绝不崩溃。
 */
object DndHelper {

    /** 是否已获得通知策略访问权限 */
    fun isGranted(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.isNotificationPolicyAccessGranted == true
    } catch (e: Exception) {
        false
    }

    /** 打开系统「勿扰访问权限」设置页 */
    fun accessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 当前勿扰过滤状态（null=读不到） */
    fun currentFilter(ctx: Context): Int? = try {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.currentInterruptionFilter
    } catch (e: Exception) {
        null
    }

    /**
     * 开启勿扰。
     * @return 进入前的过滤状态（用于退出时恢复）；未授权或失败返回 null
     */
    fun enter(ctx: Context): Int? {
        val prev = currentFilter(ctx)
        if (!isGranted(ctx)) return null
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            prev
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 退出专注：恢复到进入前的状态。
     * 若进入前本来就是勿扰，则保持不动（尊重用户自己的设置）。
     */
    fun exit(ctx: Context, prevFilter: Int?) {
        if (prevFilter == null) return
        if (prevFilter == NotificationManager.INTERRUPTION_FILTER_NONE) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (!nm.isNotificationPolicyAccessGranted) return
            nm.setInterruptionFilter(prevFilter.coerceAtLeast(NotificationManager.INTERRUPTION_FILTER_ALL))
        } catch (e: Exception) {
            // 忽略：恢复失败不应影响退出流程
        }
    }
}