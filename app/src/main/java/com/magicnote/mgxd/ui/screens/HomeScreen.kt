package com.magicnote.mgxd.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magicnote.mgxd.MGApp
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.data.prefs.UserPrefs
import com.magicnote.mgxd.screentime.ScreenTimeManager
import com.magicnote.mgxd.ui.viewmodel.CalendarViewModel
import com.magicnote.mgxd.ui.viewmodel.DiaryViewModel
import com.magicnote.mgxd.ui.viewmodel.TodoViewModel
import com.magicnote.mgxd.util.HabitEncouragement
import com.magicnote.mgxd.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    todoVm: TodoViewModel,
    calendarVm: CalendarViewModel,
    diaryVm: DiaryViewModel,
    moduleConfig: UserPrefs.ModuleConfig = UserPrefs.ModuleConfig(),
    onNavigateTo: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onAddTodo: () -> Unit,
    onAddEvent: () -> Unit,
    onAddDiary: () -> Unit
) {
    val todos by todoVm.todos.collectAsStateWithLifecycle()
    val habits by todoVm.habits.collectAsStateWithLifecycle()
    val countdowns by todoVm.countdowns.collectAsStateWithLifecycle()
    val lastCheckIn by todoVm.lastCheckIn.collectAsStateWithLifecycle()
    val events by calendarVm.events.collectAsStateWithLifecycle()
    val diaries by diaryVm.diaries.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 屏幕时间图表数据（授权后显示）
    var screenHasAccess by remember { mutableStateOf(false) }
    var screenTodayTotal by remember { mutableStateOf(0L) }
    var screenCategories by remember {
        mutableStateOf<List<ScreenTimeManager.CategoryUsage>>(emptyList())
    }
    LaunchedEffect(Unit) {
        screenHasAccess = ScreenTimeManager.hasUsageAccess(context)
        if (screenHasAccess) {
            val app = context.applicationContext as MGApp
            val repo = app.container.repository
            val overrides = repo.categoryOverrides.first()
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            screenCategories = withContext(Dispatchers.IO) {
                ScreenTimeManager.getCategoryUsage(context, start, System.currentTimeMillis(), overrides)
            }
            screenTodayTotal = withContext(Dispatchers.IO) {
                ScreenTimeManager.getTodayUsageMillis(context)
            }
        }
    }
    val today = LocalDate.now()
    // 今日待办/日程/日记：列表变化时才重算过滤，避免每次重组重复遍历
    val todayTodos = remember(todos) { todos.filter { !it.isLongTerm } }
    val todayEvents = remember(events, today) {
        events.filter {
            java.time.Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate() == today
        }
    }
    val todayDiaries = remember(diaries, today) {
        diaries.filter {
            java.time.Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).toLocalDate() == today
        }
    }
    // 同一天多条按时间正序，取最新一篇展示摘要
    val todayDiary = todayDiaries.lastOrNull()

    val completedCount = todayTodos.count { it.completed }
    val totalCount = todayTodos.size
    val progress = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Magic note", style = MaterialTheme.typography.titleLarge)
                        Text(
                            today.format(DateTimeFormatter.ofPattern("M月d日 EEEE")),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 进度卡片（待办关闭时隐藏「今日任务进度」）
            if (moduleConfig.todoEnabled) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(72.dp),
                                    strokeWidth = 8.dp,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Text(
                                    text = "$completedCount/$totalCount",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("今日任务进度", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = when {
                                        totalCount == 0 -> "今天还没有安排任务，点击 + 添加一个吧"
                                        progress == 1f -> "全部完成！太棒了 🎉"
                                        else -> "已完成 $completedCount 项，还剩 ${totalCount - completedCount} 项待办"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            // Magic AI 今日提示（待办关闭时一并隐藏，提示依赖待办数据）
            if (moduleConfig.todoEnabled) {
                item {
                    val tip = buildHomeTip(todayTodos, todayEvents, todayDiary)
                    AiTipCard(tip = tip, onClick = { onNavigateTo(4) })
                }
            }

            // 今日待办（模块关闭时整块隐藏）
            if (moduleConfig.todoEnabled) {
                item {
                    SectionHeader(title = "今日待办", count = todayTodos.size, onAdd = onAddTodo)
                }
                if (todayTodos.isEmpty()) {
                    item { EmptyHint("今天没有待办事项") }
                } else {
                    items(todayTodos.take(5)) { todo ->
                        CompactTodoRow(todo = todo, onToggle = { todoVm.toggle(context, todo) })
                    }
                    if (todayTodos.size > 5) {
                        item {
                            Text(
                                "还有 ${todayTodos.size - 5} 项… 去「待办」查看全部",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 每日打卡（有创建才显示；属于待办模块）
            if (moduleConfig.todoEnabled && habits.isNotEmpty()) {
                item {
                    SectionHeader(title = "每日打卡", count = habits.size, onAdd = { onNavigateTo(1) })
                }
                items(habits.take(5)) { habit ->
                    CompactHabitRow(
                        habit = habit,
                        onCheckIn = { todoVm.checkIn(context, habit) }
                    )
                }
            }

            // 倒数日（有创建才显示；属于待办模块）
            if (moduleConfig.todoEnabled && countdowns.isNotEmpty()) {
                item {
                    SectionHeader(title = "倒数日", count = countdowns.size, onAdd = { onNavigateTo(1) })
                }
                items(countdowns.take(5)) { countdown ->
                    CompactCountdownRow(countdown = countdown)
                }
            }

            // 今日日程（模块关闭时整块隐藏）
            if (moduleConfig.calendarEnabled) {
                item {
                    SectionHeader(title = "今日日程", count = todayEvents.size, onAdd = onAddEvent)
                }
                if (todayEvents.isEmpty()) {
                    item { EmptyHint("今天没有日程安排") }
                } else {
                    items(todayEvents.take(5)) { event ->
                        CompactEventRow(event = event)
                    }
                }
            }

            // 今日日记（模块关闭时整块隐藏）
            if (moduleConfig.diaryEnabled) {
                item {
                    SectionHeader(title = "今日日记", count = todayDiaries.size, onAdd = onAddDiary)
                }
            if (todayDiaries.isEmpty()) {
                item {
                    Card(
                        onClick = onAddDiary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("今天还没写日记，记录一下吧 →", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                item {
                    Card(
                        onClick = onAddDiary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (todayDiaries.size > 1) "今日已记 ${todayDiaries.size} 篇，继续记录 →" else "再记一篇？",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = todayDiary?.title ?: "最新日记",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = todayDiary?.content ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            }

            // 屏幕时间图表（授权后显示在主页最下方）
            if (screenHasAccess && screenTodayTotal > 0L) {
                item {
                    ScreenTimeCard(total = screenTodayTotal, categories = screenCategories)
                }
            }
        }
    }

    // 打卡成功 → 鼓励弹窗（与待办页一致）
    lastCheckIn?.let { (habit, text) ->
        AlertDialog(
            onDismissRequest = { todoVm.dismissCheckIn() },
            title = { Text("✅ 打卡成功！") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「${habit.title}」", style = MaterialTheme.typography.titleMedium)
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                Button(onClick = { todoVm.dismissCheckIn() }) { Text("太棒了 💪") }
            }
        )
    }
}

@Composable
private fun CompactHabitRow(habit: HabitEntity, onCheckIn: () -> Unit) {
    val checkedToday = habit.checkInDates.contains(LocalDate.now().toString())
    val streak = HabitEncouragement.streakOf(habit.checkInDates)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(habit.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "🔥 连续 $streak 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (checkedToday) {
                Text(
                    "✓ 已打卡",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Button(onClick = onCheckIn) { Text("打卡") }
            }
        }
    }
}

@Composable
private fun CompactCountdownRow(countdown: CountdownEntity) {
    val days = countdown.daysLeft
    val (label, color) = when {
        days > 0 -> "还有 $days 天" to MaterialTheme.colorScheme.primary
        days == 0L -> "就是今天！🎉" to MaterialTheme.colorScheme.primary
        else -> "已过 ${-days} 天" to MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                countdown.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        if (count > 0) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = "添加", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun CompactTodoRow(todo: TodoEntity, onToggle: () -> Unit) {
    // 昨天及以前仍未完成的今日待办：红字标注（与待办页一致）
    val todayStart = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val isOverdue = !todo.completed && !todo.isLongTerm && todo.createdAt < todayStart
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(
                checked = todo.completed,
                onCheckedChange = { onToggle() }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (todo.completed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    color = when {
                        todo.completed -> MaterialTheme.colorScheme.outline
                        isOverdue -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (isOverdue) {
                    Text(
                        text = "⚠️ 未完成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            todo.dueTime?.let {
                Text(
                    text = TimeUtils.formatMillis(it, "HH:mm"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun CompactEventRow(event: CalendarEventEntity) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .background(Color(event.color), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${TimeUtils.formatMillis(event.startTime, "HH:mm")} ${event.title}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ==================== Magic AI 今日提示（功能 D） ====================

/**
 * 本地规则生成今日提示：零 API 成本、即时展示
 * 规则优先级：紧急截止 > 高优先级 > 待办数量 > 空档日 > 全部完成
 */
private fun buildHomeTip(
    todos: List<TodoEntity>,
    events: List<CalendarEventEntity>,
    diary: DiaryEntity?
): String {
    val pending = todos.filter { !it.completed }
    val now = System.currentTimeMillis()
    return when {
        pending.isEmpty() && events.isEmpty() ->
            if (diary == null) "今天还没安排，要不要记个待办，让今天有点小目标？🪄"
            else "今天很轻松，把好心情写进日记，Magic AI 会一直陪着你 🌈"

        pending.isEmpty() ->
            "今日任务全部搞定！给努力的自己记一篇日记吧 ✨"

        else -> {
            val soonest = pending.filter { it.dueTime != null }.minByOrNull { it.dueTime!! }
            val high = pending.firstOrNull { it.priority == 2 }
            when {
                soonest != null -> {
                    val remain = soonest.dueTime!! - now
                    val label = when {
                        remain <= 0 -> "已经超时啦"
                        remain < 60 * 60 * 1000 -> "还剩 ${remain / 60 / 1000} 分钟"
                        remain < 24 * 60 * 60 * 1000 -> "还剩 ${remain / 3600 / 1000} 小时"
                        else -> "截止 ${TimeUtils.formatMillis(soonest.dueTime!!)}"
                    }
                    "建议先搞定「${soonest.title}」，$label ⏰"
                }

                high != null -> "高优先级任务「${high.title}」还没动，先啃硬骨头 💪"

                else -> "今天还有 ${pending.size} 件事，从最早截止的开始吧 🚀"
            }
        }
    }
}

@Composable
private fun AiTipCard(tip: String, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF0E8FF)
    val accent = if (dark) Color(0xFFB49BFF) else Color(0xFF5B3DF5)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = accent
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Magic AI 今日提示",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ==================== 屏幕时间图表（主页最下方） ====================

@Composable
private fun ScreenTimeCard(total: Long, categories: List<ScreenTimeManager.CategoryUsage>) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF0E8FF)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("今日屏幕时间", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    TimeUtils.formatDuration(total),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (categories.isEmpty()) {
                Text(
                    "今日暂无使用数据，用一会儿手机再来看看吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DonutChart(categories = categories, modifier = Modifier.size(130.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        categories.forEach { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(screenCategoryColor(c.category), RoundedCornerShape(3.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    c.category.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                val pct = if (total > 0) (c.millis * 100 / total) else 0
                                Text(
                                    "${TimeUtils.formatDuration(c.millis)} $pct%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "分类可在 设置 → 屏幕时间 自定义 · 每日 22:00 日报告 / 周日 08:00 周报告",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 类别环形图（Canvas 自绘，零依赖） */
@Composable
private fun DonutChart(categories: List<ScreenTimeManager.CategoryUsage>, modifier: Modifier = Modifier) {
    val total = categories.sumOf { it.millis }.coerceAtLeast(1L)
    Canvas(modifier = modifier) {
        val stroke = 16.dp.toPx()
        val inset = stroke / 2f
        var startAngle = -90f
        categories.forEach { item ->
            val sweep = item.millis.toFloat() / total * 360f
            drawArc(
                color = screenCategoryColor(item.category),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke)
            )
            startAngle += sweep
        }
    }
}

private fun screenCategoryColor(category: ScreenTimeManager.AppCategory): Color = when (category) {
    ScreenTimeManager.AppCategory.ENTERTAINMENT -> Color(0xFF9C6BFF)
    ScreenTimeManager.AppCategory.SOCIAL -> Color(0xFF4C9EFF)
    ScreenTimeManager.AppCategory.SHOPPING -> Color(0xFFFF9F43)
    ScreenTimeManager.AppCategory.TOOLS -> Color(0xFF4CD97B)
    ScreenTimeManager.AppCategory.STUDY -> Color(0xFF00C2C7)
    ScreenTimeManager.AppCategory.OTHER -> Color(0xFF9E9E9E)
}