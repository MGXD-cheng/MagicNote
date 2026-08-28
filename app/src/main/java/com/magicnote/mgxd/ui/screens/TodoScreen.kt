package com.magicnote.mgxd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.ui.components.ConfirmDialog
import com.magicnote.mgxd.ui.components.DateTimePickerDialog
import com.magicnote.mgxd.ui.components.EmptyState
import com.magicnote.mgxd.ui.components.PrioritySelector
import com.magicnote.mgxd.ui.viewmodel.TodoViewModel
import com.magicnote.mgxd.util.HabitEncouragement
import com.magicnote.mgxd.util.TimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ==================== 待办列表页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    vm: TodoViewModel,
    onAddClick: () -> Unit
) {
    val todos by vm.todos.collectAsStateWithLifecycle()
    val habits by vm.habits.collectAsStateWithLifecycle()
    val countdowns by vm.countdowns.collectAsStateWithLifecycle()
    val lastCheckIn by vm.lastCheckIn.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var deleteTarget by remember { mutableStateOf<TodoEntity?>(null) }
    var editTarget by remember { mutableStateOf<TodoEntity?>(null) }
    var showAiQuickAdd by remember { mutableStateOf(false) }
    // 打卡相关状态
    var showAddHabit by remember { mutableStateOf(false) }
    var editHabitTarget by remember { mutableStateOf<HabitEntity?>(null) }
    var habitDetail by remember { mutableStateOf<HabitEntity?>(null) }
    var deleteHabitTarget by remember { mutableStateOf<HabitEntity?>(null) }
    // 倒数日相关状态
    var showAddCountdown by remember { mutableStateOf(false) }
    var editCountdownTarget by remember { mutableStateOf<CountdownEntity?>(null) }
    var deleteCountdownTarget by remember { mutableStateOf<CountdownEntity?>(null) }
    // 0=今日待办（默认） 1=长期待办 2=每日打卡 3=倒数日
    var tab by rememberSaveable { mutableStateOf(0) }
    val todayTodos = todos.filter { !it.isLongTerm }
    val longTermTodos = todos.filter { it.isLongTerm }
    val visible = if (tab == 0) todayTodos else longTermTodos

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                when (tab) {
                    2 -> showAddHabit = true
                    3 -> showAddCountdown = true
                    else -> onAddClick()
                }
            }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = when (tab) {
                        2 -> "新建打卡"
                        3 -> "新建倒数日"
                        else -> "添加待办"
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("今日待办") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("长期待办") }
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text("每日打卡") }
                )
                Tab(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    text = { Text("倒数日") }
                )
            }
            when (tab) {
                2 -> {
                    // ===== 每日打卡列表 =====
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "🔥 每日打卡",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "创建习惯 → 每天打卡 → 连续坚持有鼓励，还能查看已打卡日期",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        if (habits.isEmpty()) {
                            item {
                                EmptyState(
                                    text = "还没有打卡习惯\n点击右下角 + 创建一个吧，如「每天背 20 个单词」",
                                    icon = Icons.Outlined.Inbox
                                )
                            }
                        } else {
                            items(habits, key = { it.id }) { habit ->
                                HabitCard(
                                    habit = habit,
                                    checkedToday = habit.checkInDates.contains(LocalDate.now().toString()),
                                    streak = HabitEncouragement.streakOf(habit.checkInDates),
                                    onCheckIn = { vm.checkIn(context, habit) },
                                    onHistory = { habitDetail = habit },
                                    onEdit = { editHabitTarget = habit },
                                    onDelete = { deleteHabitTarget = habit }
                                )
                            }
                        }
                    }
                }
                3 -> {
                    // ===== 倒数日列表 =====
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "⏳ 倒数日",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "记录重要的日子：距离高考、生日、纪念日还有多少天",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        if (countdowns.isEmpty()) {
                            item {
                                EmptyState(
                                    text = "还没有倒数日\n点右下角 + 记录一个重要日子吧，如「距离高考」",
                                    icon = Icons.Outlined.Inbox
                                )
                            }
                        } else {
                            items(countdowns, key = { it.id }) { countdown ->
                                CountdownCard(
                                    countdown = countdown,
                                    onEdit = { editCountdownTarget = countdown },
                                    onDelete = { deleteCountdownTarget = countdown }
                                )
                            }
                        }
                    }
                }
                else -> {
                    // ===== 待办列表 =====
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Magic AI 一句话建待办入口
                        item {
                            Card(
                                onClick = { showAiQuickAdd = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
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
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "Magic AI 一句话建待办",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            "试试：「明天下午 3 点去医院复查」",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }

                        if (visible.isEmpty()) {
                            item {
                                EmptyState(
                                    text = if (tab == 0) {
                                        "今天还没有待办事项\n点击右下角 + 添加第一个任务吧"
                                    } else {
                                        "还没有长期待办\n适合放持续进行的目标，如「每天背 20 个单词」"
                                    },
                                    icon = Icons.Outlined.Inbox
                                )
                            }
                        } else {
                            items(visible, key = { it.id }) { todo ->
                                TodoItemCard(
                                    todo = todo,
                                    onToggle = { vm.toggle(context, todo) },
                                    onEdit = { editTarget = todo },
                                    onDelete = { deleteTarget = todo }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { todo ->
        ConfirmDialog(
            title = "删除待办",
            text = "确定要删除「${todo.title}」吗？",
            onConfirm = {
                vm.delete(context, todo)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // 点击待办卡片 → 编辑（预填原内容）
    editTarget?.let { todo ->
        AddTodoDialog(
            initialTitle = todo.title,
            initialDescription = todo.description.orEmpty(),
            initialPriority = todo.priority,
            initialDueTime = todo.dueTime,
            initialRemindAt = todo.remindAt,
            initialIsLongTerm = todo.isLongTerm,
            onDismiss = { editTarget = null },
            onConfirm = { title, desc, due, remind, priority, isLongTerm ->
                vm.updateTodo(
                    context,
                    todo.copy(
                        title = title,
                        description = desc,
                        // 长期待办不设截止/提醒
                        dueTime = if (isLongTerm) null else due,
                        remindAt = if (isLongTerm) null else remind,
                        priority = priority,
                        isLongTerm = isLongTerm
                    )
                )
                editTarget = null
            }
        )
    }

    if (showAiQuickAdd) {
        AiQuickAddDialog(
            vm = vm,
            onDismiss = {
                vm.dismissAiParse()
                showAiQuickAdd = false
            }
        )
    }

    // ===== 每日打卡对话框 =====
    if (showAddHabit) {
        AddHabitDialog(
            onDismiss = { showAddHabit = false },
            onConfirm = { title, remindHour, remindMinute, targetDays ->
                vm.addHabit(context, title, remindHour, remindMinute, targetDays)
                showAddHabit = false
            }
        )
    }
    editHabitTarget?.let { habit ->
        AddHabitDialog(
            initialTitle = habit.title,
            initialRemindHour = habit.remindHour,
            initialRemindMinute = habit.remindMinute,
            initialTargetDays = habit.targetDays,
            onDismiss = { editHabitTarget = null },
            onConfirm = { title, remindHour, remindMinute, targetDays ->
                vm.editHabit(context, habit, title, remindHour, remindMinute, targetDays)
                editHabitTarget = null
            }
        )
    }
    habitDetail?.let { habit ->
        HabitHistoryDialog(
            habit = habit,
            onDismiss = { habitDetail = null }
        )
    }
    deleteHabitTarget?.let { habit ->
        ConfirmDialog(
            title = "删除打卡习惯",
            text = "确定要删除「${habit.title}」吗？已打卡记录也会一并删除",
            onConfirm = {
                vm.deleteHabit(context, habit)
                deleteHabitTarget = null
            },
            onDismiss = { deleteHabitTarget = null }
        )
    }
    // ===== 倒数日对话框 =====
    if (showAddCountdown) {
        AddCountdownDialog(
            onDismiss = { showAddCountdown = false },
            onConfirm = { title, targetDate ->
                vm.addCountdown(title, targetDate)
                showAddCountdown = false
            }
        )
    }
    editCountdownTarget?.let { countdown ->
        AddCountdownDialog(
            initialTitle = countdown.title,
            initialDate = countdown.targetDate,
            onDismiss = { editCountdownTarget = null },
            onConfirm = { title, targetDate ->
                vm.editCountdown(countdown, title, targetDate)
                editCountdownTarget = null
            }
        )
    }
    deleteCountdownTarget?.let { countdown ->
        ConfirmDialog(
            title = "删除倒数日",
            text = "确定要删除「${countdown.title}」吗？",
            onConfirm = {
                vm.deleteCountdown(countdown)
                deleteCountdownTarget = null
            },
            onDismiss = { deleteCountdownTarget = null }
        )
    }
    // 打卡成功 → 鼓励弹窗
    lastCheckIn?.let { (habit, text) ->
        AlertDialog(
            onDismissRequest = { vm.dismissCheckIn() },
            title = { Text("✅ 打卡成功！") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「${habit.title}」", style = MaterialTheme.typography.titleMedium)
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                Button(onClick = { vm.dismissCheckIn() }) { Text("太棒了 💪") }
            }
        )
    }
}

// ==================== 每日打卡卡片 ====================

@Composable
private fun HabitCard(
    habit: HabitEntity,
    checkedToday: Boolean,
    streak: Int,
    onCheckIn: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onHistory,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    habit.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "🔥 连续 $streak 天",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "累计 ${habit.checkInDates.size} 天",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (habit.targetDays > 0) {
                val progress = (habit.checkInDates.size.toFloat() / habit.targetDays).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                if (habit.checkInDates.size >= habit.targetDays) {
                    Text(
                        "🎉 已达成 ${habit.targetDays} 天目标！",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "目标 ${habit.targetDays} 天 · 还差 ${habit.targetDays - habit.checkInDates.size} 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (habit.remindHour >= 0) {
                Text(
                    "⏰ 每天 ${String.format("%02d:%02d", habit.remindHour, habit.remindMinute)} 提醒",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Button(
                onClick = onCheckIn,
                enabled = !checkedToday,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checkedToday) "✓ 今日已打卡" else "今日打卡")
            }
            Text(
                "点击卡片查看已打卡日期",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ==================== 新建/编辑打卡对话框 ====================

@Composable
private fun AddHabitDialog(
    initialTitle: String = "",
    initialRemindHour: Int = -1,
    initialRemindMinute: Int = 0,
    initialTargetDays: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (title: String, remindHour: Int, remindMinute: Int, targetDays: Int) -> Unit
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var remindEnabled by remember(initialRemindHour) { mutableStateOf(initialRemindHour >= 0) }
    var hour by remember(initialRemindHour.coerceAtLeast(0)) { mutableIntStateOf(initialRemindHour.coerceAtLeast(0)) }
    var minute by remember(initialRemindMinute) { mutableIntStateOf(initialRemindMinute) }
    var targetDays by remember(initialTargetDays) {
        mutableStateOf(if (initialTargetDays > 0) initialTargetDays.toString() else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTitle.isBlank()) "新建每日打卡" else "编辑每日打卡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("打卡名称") },
                    placeholder = { Text("如：每天背 20 个单词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("每日提醒", style = MaterialTheme.typography.titleMedium)
                        Text("到点推送通知提醒打卡", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = remindEnabled,
                        onCheckedChange = { remindEnabled = it }
                    )
                }
                if (remindEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("时", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { hour = (hour + 23) % 24 }) { Text("−") }
                        Text(
                            String.format("%02d", hour),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        OutlinedButton(onClick = { hour = (hour + 1) % 24 }) { Text("+") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("分", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { minute = (minute + 59) % 60 }) { Text("−") }
                        Text(
                            String.format("%02d", minute),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        OutlinedButton(onClick = { minute = (minute + 1) % 60 }) { Text("+") }
                    }
                }
                OutlinedTextField(
                    value = targetDays,
                    onValueChange = { targetDays = it.filter { c -> c.isDigit() } },
                    label = { Text("目标天数（可不填）") },
                    placeholder = { Text("留空 = 不限天数，如 21/30/100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(
                            title.trim(),
                            if (remindEnabled) hour else -1,
                            minute,
                            targetDays.toIntOrNull() ?: 0
                        )
                    }
                },
                enabled = title.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ==================== 已打卡日期查看对话框 ====================

@Composable
private fun HabitHistoryDialog(
    habit: HabitEntity,
    onDismiss: () -> Unit
) {
    val dates = habit.checkInDates.sortedDescending()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打卡记录 · ${habit.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "累计 ${dates.size} 天 · 连续 ${HabitEncouragement.streakOf(habit.checkInDates)} 天",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (habit.targetDays > 0) {
                    Text(
                        if (dates.size >= habit.targetDays) {
                            "🎉 已达成 ${habit.targetDays} 天目标！"
                        } else {
                            "距目标 ${habit.targetDays} 天还差 ${habit.targetDays - dates.size} 天"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (dates.isEmpty()) {
                    Text(
                        "还没有打卡记录，今天就开始吧 💪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 300.dp)
                    ) {
                        dates.forEach { d ->
                            Text(
                                d,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// ==================== 倒数日卡片 ====================

@Composable
private fun CountdownCard(
    countdown: CountdownEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val days = countdown.daysLeft
    val (label, color) = when {
        days > 0 -> "还有 $days 天" to MaterialTheme.colorScheme.primary
        days == 0L -> "就是今天！🎉" to MaterialTheme.colorScheme.primary
        else -> "已过 ${-days} 天" to MaterialTheme.colorScheme.outline
    }
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(countdown.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${TimeUtils.formatMillis(countdown.targetDate, "yyyy-MM-dd")} · $label",
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ==================== 新建/编辑倒数日对话框 ====================

@Composable
private fun AddCountdownDialog(
    initialTitle: String = "",
    initialDate: Long? = null,
    onDismiss: () -> Unit,
    onConfirm: (title: String, targetDate: Long) -> Unit
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var date by remember(initialDate) { mutableStateOf(initialDate) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTitle.isBlank()) "新建倒数日" else "编辑倒数日") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("名称") },
                    placeholder = { Text("如：距离高考 / 生日 / 纪念日") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(date?.let { TimeUtils.formatMillis(it, "yyyy-MM-dd") } ?: "选择目标日期")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && date != null) {
                        // 归一化到目标日当天 0 点，避免时间差影响天数计算
                        val dayStart = java.time.Instant.ofEpochMilli(date!!)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                        onConfirm(title.trim(), dayStart)
                    }
                },
                enabled = title.isNotBlank() && date != null
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showPicker) {
        DateTimePickerDialog(
            title = "选择目标日期",
            initialMillis = date,
            onConfirm = { date = it; showPicker = false },
            onDismiss = { showPicker = false }
        )
    }
}

// ==================== Magic AI 一句话建待办对话框（功能 A） ====================

@Composable
fun AiQuickAddDialog(
    vm: TodoViewModel,
    onDismiss: () -> Unit
) {
    val parseState by vm.aiParse.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var isLongTerm by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val parseError = parseState.error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🪄 Magic AI 一句话建待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TodoTypeSelector(isLongTerm = isLongTerm, onSelect = { isLongTerm = it })
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("用一句话描述任务") },
                    placeholder = { Text("例如：明天下午 3 点去医院复查，记得带病历") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                when {
                    parseState.parsing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Magic AI 正在解析…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    parseError != null -> {
                        Text(
                            parseError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    parseState.result != null -> {
                        val r = parseState.result!!
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("✨ 解析结果", style = MaterialTheme.typography.labelMedium)
                                Text("标题：${r.title}", style = MaterialTheme.typography.bodyLarge)
                                r.description?.takeIf { it.isNotBlank() }?.let {
                                    Text("备注：$it", style = MaterialTheme.typography.bodyMedium)
                                }
                                r.dueTime?.let {
                                    Text("截止：${TimeUtils.formatMillis(it)}", style = MaterialTheme.typography.bodyMedium)
                                }
                                r.remindAt?.let {
                                    Text("提醒：${TimeUtils.formatMillis(it)}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    "优先级：${when (r.priority) { 2 -> "高"; 0 -> "低"; else -> "中" }}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "确认无误即可一键创建；想改就重新输入再解析",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (parseState.result != null) {
                Button(onClick = {
                    vm.confirmAiTodo(context, isLongTerm)
                    onDismiss()
                }) { Text("确认创建") }
            } else {
                Button(
                    onClick = { vm.aiParseTodo(input) },
                    enabled = input.isNotBlank() && !parseState.parsing
                ) { Text("Magic AI 解析") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun TodoItemCard(
    todo: TodoEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    // 每日 0 点清理后，昨天及以前仍未完成的今日待办：红字标注「未完成」
    val todayStart = remember {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val isOverdue = !todo.completed && !todo.isLongTerm && todo.createdAt < todayStart
    val priorityColor = when {
        isOverdue -> MaterialTheme.colorScheme.error
        todo.priority == 2 -> MaterialTheme.colorScheme.error
        todo.priority == 1 -> Color(0xFFFFB74D)
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = todo.completed,
                onCheckedChange = { onToggle() }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
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
                if (todo.isLongTerm) {
                    Text(
                        text = "📌 长期待办",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (todo.source == "magic_ai") {
                    Text(
                        text = "由 magic ai 创建",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                todo.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    todo.dueTime?.let { due ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Event,
                                contentDescription = null,
                                modifier = Modifier.height(14.dp).width(14.dp),
                                tint = priorityColor
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "截止 ${TimeUtils.formatMillis(due)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = priorityColor
                            )
                        }
                    }
                    todo.remindAt?.let { remind ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                modifier = Modifier.height(14.dp).width(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "提醒 ${TimeUtils.formatMillis(remind)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ==================== 新增/编辑待办对话框 ====================

/** 待办类型切换按钮组（今日待办 / 长期待办） */
@Composable
fun TodoTypeSelector(
    isLongTerm: Boolean,
    onSelect: (Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onSelect(false) },
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = if (!isLongTerm) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.weight(1f)
        ) { Text("今日待办") }
        OutlinedButton(
            onClick = { onSelect(true) },
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = if (isLongTerm) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.weight(1f)
        ) { Text("📌 长期待办") }
    }
}

@Composable
fun AddTodoDialog(
    initialTitle: String = "",
    initialDescription: String = "",
    initialPriority: Int = 1,
    initialDueTime: Long? = null,
    initialRemindAt: Long? = null,
    initialIsLongTerm: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String?, dueTime: Long?, remindAt: Long?, priority: Int, isLongTerm: Boolean) -> Unit
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var priority by remember(initialPriority) { mutableStateOf(initialPriority) }
    var dueTime by remember(initialDueTime) { mutableStateOf(initialDueTime) }
    var remindAt by remember(initialRemindAt) { mutableStateOf(initialRemindAt) }
    var isLongTerm by remember(initialIsLongTerm) { mutableStateOf(initialIsLongTerm) }
    var showDuePicker by remember { mutableStateOf(false) }
    var showRemindPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTitle.isBlank()) "新建待办" else "编辑待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TodoTypeSelector(isLongTerm = isLongTerm, onSelect = { isLongTerm = it })
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text("优先级", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    PrioritySelector(selected = priority, onSelect = { priority = it })
                }
                if (!isLongTerm) {
                    // 长期待办无固定日期，不设置截止/提醒
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showDuePicker = true }) {
                            Text(dueTime?.let { "截止：${TimeUtils.formatMillis(it)}" } ?: "设截止时间")
                        }
                        OutlinedButton(onClick = { showRemindPicker = true }) {
                            Text(remindAt?.let { "提醒：${TimeUtils.formatMillis(it)}" } ?: "设提醒")
                        }
                    }
                } else {
                    Text(
                        "长期待办适合持续进行的目标，如「每天背 20 个单词」",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim(), description.trim().ifBlank { null }, dueTime, remindAt, priority, isLongTerm)
                    }
                },
                enabled = title.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showDuePicker) {
        DateTimePickerDialog(
            title = "设置截止时间",
            initialMillis = dueTime,
            onConfirm = { dueTime = it; showDuePicker = false },
            onDismiss = { showDuePicker = false }
        )
    }
    if (showRemindPicker) {
        DateTimePickerDialog(
            title = "设置提醒时间",
            initialMillis = remindAt,
            onConfirm = { remindAt = it; showRemindPicker = false },
            onDismiss = { showRemindPicker = false }
        )
    }
}