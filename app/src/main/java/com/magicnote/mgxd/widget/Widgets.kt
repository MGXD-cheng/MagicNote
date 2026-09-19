package com.magicnote.mgxd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.magicnote.mgxd.MGApp
import com.magicnote.mgxd.MainActivity
import com.magicnote.mgxd.R
import com.magicnote.mgxd.data.repo.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun repoOf(context: Context): AppRepository =
    (context.applicationContext as MGApp).container.repository

private fun pendingFlags(): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

private fun openAppPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(context, 0, intent, pendingFlags())
}

// ============================================================
// 今日待办组件：展示今日未完成待办，支持在桌面直接点勾完成
// ============================================================
class TodoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val id = intent.getLongExtra(EXTRA_ID, 0L)
            if (id > 0) {
                val pending = goAsync()
                widgetScope.launch {
                    try {
                        val repo = repoOf(context)
                        val todo = repo.observeTodos().first().firstOrNull { it.id == id }
                        if (todo != null) {
                            repo.updateTodo(todo.copy(completed = !todo.completed))
                        }
                    } catch (_: Exception) {
                    } finally {
                        pending.finish()
                    }
                    refreshAll(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.magicnote.mgxd.widget.ACTION_TOGGLE_TODO"
        const val EXTRA_ID = "extra_todo_id"

        fun refreshAll(context: Context) {
            val cls = TodoWidgetProvider::class.java
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, cls)).forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            widgetScope.launch {
                val views = RemoteViews(context.packageName, R.layout.widget_todo)
                try {
                    val repo = repoOf(context)
                    val pending = repo.observeTodos().first()
                        .filter { !it.isLongTerm && !it.completed }
                        .sortedWith(compareBy({ it.dueTime ?: Long.MAX_VALUE }, { -it.priority }))
                    val shown = pending.take(3)

                    views.setTextViewText(
                        R.id.widget_todo_count,
                        if (pending.isEmpty()) "" else pending.size.toString() + " 项"
                    )
                    views.setViewVisibility(
                        R.id.widget_todo_empty,
                        if (shown.isEmpty()) View.VISIBLE else View.GONE
                    )

                    val rows = listOf(
                        Triple(R.id.widget_todo_row1, R.id.widget_todo_text1, R.id.widget_todo_check1),
                        Triple(R.id.widget_todo_row2, R.id.widget_todo_text2, R.id.widget_todo_check2),
                        Triple(R.id.widget_todo_row3, R.id.widget_todo_text3, R.id.widget_todo_check3)
                    )
                    rows.forEachIndexed { index, (rowId, textId, checkId) ->
                        val todo = shown.getOrNull(index)
                        if (todo == null) {
                            views.setViewVisibility(rowId, View.GONE)
                        } else {
                            views.setViewVisibility(rowId, View.VISIBLE)
                            views.setTextViewText(textId, todo.title)
                            val toggle = toggleIntent(context, todo.id)
                            views.setOnClickPendingIntent(checkId, toggle)
                            views.setOnClickPendingIntent(rowId, toggle)
                        }
                    }

                    val open = openAppPendingIntent(context)
                    views.setOnClickPendingIntent(R.id.widget_todo_title, open)
                    views.setOnClickPendingIntent(R.id.widget_todo_footer, open)
                } catch (_: Exception) {
                }
                mgr.updateAppWidget(widgetId, views)
            }
        }

        private fun toggleIntent(context: Context, id: Long): PendingIntent {
            val intent = Intent(context, TodoWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
                data = Uri.parse("magicnote://todo/$id")
                putExtra(EXTRA_ID, id)
            }
            return PendingIntent.getBroadcast(context, id.toInt(), intent, pendingFlags())
        }
    }
}

// ============================================================
// 倒数日组件：醒目展示最近的两个倒数日
// ============================================================
class CountdownWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, CountdownWidgetProvider::class.java))
                .forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            widgetScope.launch {
                val views = RemoteViews(context.packageName, R.layout.widget_countdown)
                try {
                    val repo = repoOf(context)
                    val list = repo.observeCountdowns().first()
                        .sortedBy { it.daysLeft }
                        .take(2)

                    views.setViewVisibility(
                        R.id.widget_cd_empty,
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                    )
                    val rows = listOf(
                        Triple(R.id.widget_cd_row1, R.id.widget_cd_name1, R.id.widget_cd_days1),
                        Triple(R.id.widget_cd_row2, R.id.widget_cd_name2, R.id.widget_cd_days2)
                    )
                    rows.forEachIndexed { index, (rowId, nameId, daysId) ->
                        val item = list.getOrNull(index)
                        if (item == null) {
                            views.setViewVisibility(rowId, View.GONE)
                        } else {
                            views.setViewVisibility(rowId, View.VISIBLE)
                            views.setTextViewText(nameId, item.title)
                            views.setTextViewText(daysId, daysText(item.daysLeft))
                        }
                    }
                    val open = openAppPendingIntent(context)
                    views.setOnClickPendingIntent(R.id.widget_cd_title, open)
                    views.setOnClickPendingIntent(R.id.widget_cd_root, open)
                } catch (_: Exception) {
                }
                mgr.updateAppWidget(widgetId, views)
            }
        }

        private fun daysText(days: Long): String = when {
            days > 0 -> "还有 " + days + " 天"
            days == 0L -> "就是今天 🎉"
            else -> "已过 " + (-days) + " 天"
        }
    }
}

// ============================================================
// 一句话记录组件：点击弹出输入框，AI 解析后自动分类存入待办 / 日记
// ============================================================
class QuickNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, QuickNoteWidgetProvider::class.java))
                .forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_quicknote)
            val intent = Intent(context, QuickNoteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.widget_quicknote_root,
                PendingIntent.getActivity(context, 0, intent, pendingFlags())
            )
            mgr.updateAppWidget(widgetId, views)
        }
    }
}