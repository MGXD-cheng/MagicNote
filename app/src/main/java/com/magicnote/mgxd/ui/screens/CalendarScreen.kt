package com.magicnote.mgxd.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.ui.components.ColorSelector
import com.magicnote.mgxd.ui.components.ConfirmDialog
import com.magicnote.mgxd.ui.components.DateTimePickerDialog
import com.magicnote.mgxd.ui.components.EmptyState
import com.magicnote.mgxd.ui.components.EVENT_COLORS
import com.magicnote.mgxd.ui.viewmodel.CalendarViewModel
import com.magicnote.mgxd.ui.viewmodel.CalendarViewModel.PlanState
import com.magicnote.mgxd.util.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 倒数日标记色：红色 */
private val COUNTDOWN_RED = Color(0xFFE53935)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    vm: CalendarViewModel,
    countdowns: StateFlow<List<CountdownEntity>>,
    onAddClick: () -> Unit
) {
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val countdownList by countdowns.collectAsStateWithLifecycle()
    var currentMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }
    var deleteTarget by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var editTarget by remember { mutableStateOf<CalendarEventEntity?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val selectedEvents = remember(events, selectedDate) { vm.eventsOn(selectedDate) }
    // 倒数日日期集合（月视图红色圆点）+ 选中日当天的倒数日
    val countdownDates = remember(countdownList) {
        countdownList.map { Instant.ofEpochMilli(it.targetDate).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
    }
    val selectedCountdowns = remember(countdownList, selectedDate) {
        countdownList.filter { Instant.ofEpochMilli(it.targetDate).atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate }
    }
    var showPlanDialog by remember { mutableStateOf(false) }
    // 日历折叠状态：上滑日程列表自动折叠为单周视图；点击展开按钮恢复
    var calendarCollapsed by remember { mutableStateOf(false) }
    // 专注模式：当前进入专注的日程
    var focusEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    val eventListState = rememberLazyListState()
    val shouldCollapse by remember {
        derivedStateOf {
            eventListState.firstVisibleItemIndex > 0 || eventListState.firstVisibleItemScrollOffset > 60
        }
    }
    LaunchedEffect(shouldCollapse) {
        if (shouldCollapse) calendarCollapsed = true
    }

    // 时间冲突自动对齐提示（一次性 Toast）
    val adjustHint by vm.lastAdjustHint.collectAsStateWithLifecycle()
    LaunchedEffect(adjustHint) {
        adjustHint?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeAdjustHint()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        MonthHeader(
            month = currentMonth,
            collapsed = calendarCollapsed,
            onPrev = { currentMonth = currentMonth.minusMonths(1) },
            onNext = { currentMonth = currentMonth.plusMonths(1) },
            onPlanClick = { showPlanDialog = true },
            onToggleCollapse = { calendarCollapsed = !calendarCollapsed }
        )
        MonthGrid(
            month = currentMonth,
            selectedDate = selectedDate,
            events = events,
            countdownDates = countdownDates,
            collapsed = calendarCollapsed,
            onSelectDate = { vm.selectDate(it) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = selectedDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE")),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = eventListState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 当天有倒数日：红色卡片置顶展示
            if (selectedCountdowns.isNotEmpty()) {
                items(selectedCountdowns, key = { "cd_${it.id}" }) { countdown ->
                    CountdownDayCard(countdown = countdown)
                }
            }
            if (selectedEvents.isEmpty()) {
                item {
                    EmptyState(
                        text = "当天没有日程",
                        icon = Icons.Outlined.Event
                    )
                }
            } else {
                items(selectedEvents, key = { it.id }) { event ->
                    EventCard(
                        event = event,
                        onClick = { editTarget = event },
                        onDelete = { deleteTarget = event },
                        onFocus = { focusEvent = event }
                    )
                }
            }
        }
    }

    // 专注模式：全屏横屏大字时钟
    focusEvent?.let { event ->
        FocusModeScreen(event = event, onExit = { focusEvent = null })
    }

    deleteTarget?.let { event ->
        ConfirmDialog(
            title = "删除日程",
            text = "确定要删除「${event.title}」吗？",
            onConfirm = {
                vm.deleteEvent(event)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // AI 规划：输入需求/资源/截止时间/优先级 → 生成方案 → 采纳自动登记
    if (showPlanDialog) {
        PlanDialog(
            vm = vm,
            onDismiss = {
                showPlanDialog = false
                vm.resetPlan()
            }
        )
    }

    // 点击日程卡片 → 编辑（预填原内容）
    editTarget?.let { event ->
        AddEventDialog(
            initialTitle = event.title,
            initialDescription = event.description.orEmpty(),
            initialStart = event.startTime,
            initialEnd = event.endTime,
            initialColor = event.color,
            initialRemindMinutes = event.remindMinutes,
            onDismiss = { editTarget = null },
            onConfirm = { title, start, end, desc, color, remindMinutes ->
                vm.updateEvent(
                    event.copy(
                        title = title,
                        startTime = start,
                        endTime = end,
                        description = desc,
                        color = color,
                        remindMinutes = remindMinutes
                    )
                )
                editTarget = null
            }
        )
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    collapsed: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlanClick: () -> Unit,
    onToggleCollapse: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上个月")
        }
        Text(
            text = "${month.year}年${month.monthValue}月",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下个月")
        }
        IconButton(onClick = onToggleCollapse) {
            Icon(
                if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (collapsed) "展开完整日历" else "折叠为单周",
                tint = MaterialTheme.colorScheme.outline
            )
        }
        IconButton(onClick = onPlanClick) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = "AI 规划",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
            Text(
                text = day,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    events: List<CalendarEventEntity>,
    countdownDates: Set<LocalDate>,
    collapsed: Boolean,
    onSelectDate: (LocalDate) -> Unit
) {
    val firstDay = month.atDay(1)
    // 周一为一周开始：周一=0 ... 周日=6
    val leading = (firstDay.dayOfWeek.value + 6) % 7
    val daysInMonth = month.lengthOfMonth()
    val cells = leading + daysInMonth

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (collapsed) {
            // 折叠模式：只显示选中日期所在的一周（周一 ~ 周日）
            val startOfWeek = selectedDate.minusDays(((selectedDate.dayOfWeek.value + 6) % 7).toLong())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (offset in 0 until 7) {
                    val date = startOfWeek.plusDays(offset.toLong())
                    DayCell(
                        date = date,
                        isSelected = date == selectedDate,
                        isToday = date == LocalDate.now(),
                        hasEvents = events.any { isSameDay(it.startTime, date) },
                        hasCountdown = date in countdownDates,
                        onClick = { onSelectDate(date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            return@Column
        }
        var row = 0
        while (row * 7 < cells) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (col in 0 until 7) {
                    val index = row * 7 + col
                    val dayOffset = index - leading
                    if (dayOffset in 0 until daysInMonth) {
                        val date = month.atDay(dayOffset + 1)
                        DayCell(
                            date = date,
                            isSelected = date == selectedDate,
                            isToday = date == LocalDate.now(),
                            hasEvents = events.any { isSameDay(it.startTime, date) },
                            hasCountdown = date in countdownDates,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f).height(48.dp))
                    }
                }
            }
            row++
        }
    }
}

private fun isSameDay(millis: Long, date: LocalDate): Boolean =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate() == date

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    hasCountdown: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .background(bgColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // 日程标记（主色圆点）
                Box(
                    modifier = Modifier
                        .size(if (hasEvents) 5.dp else 0.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                )
                // 倒数日标记（红色圆点）
                Box(
                    modifier = Modifier
                        .size(if (hasCountdown) 5.dp else 0.dp)
                        .background(COUNTDOWN_RED, CircleShape)
                )
            }
        }
    }
}

/** 当日倒数日卡片（红色标识） */
@Composable
private fun CountdownDayCard(countdown: CountdownEntity) {
    val days = countdown.daysLeft
    val (label, color) = when {
        days > 0 -> "还有 $days 天" to COUNTDOWN_RED
        days == 0L -> "就是今天！🎉" to COUNTDOWN_RED
        else -> "已过 ${-days} 天" to MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = COUNTDOWN_RED.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(COUNTDOWN_RED, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "⏳ ${countdown.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = COUNTDOWN_RED
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun EventCard(
    event: CalendarEventEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFocus: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(Color(event.color), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${TimeUtils.formatMillis(event.startTime, "HH:mm")} - ${TimeUtils.formatMillis(event.endTime, "HH:mm")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                event.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
                if (event.source == "magic_ai") {
                    Text(
                        text = "由 magic ai 创建",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = onFocus) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = "专注模式",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ==================== AI 规划对话框 ====================

@Composable
private fun PlanDialog(
    vm: CalendarViewModel,
    onDismiss: () -> Unit
) {
    val planState by vm.planState.collectAsStateWithLifecycle()
    val applyResult by vm.planApplyResult.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var requirement by remember { mutableStateOf("") }
    var resources by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf<Long?>(null) }
    var priority by remember { mutableStateOf(1) }
    var showDeadlinePicker by remember { mutableStateOf(false) }

    LaunchedEffect(applyResult) {
        applyResult?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumePlanApplyResult()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✨ AI 规划") },
        text = {
            when (val st = planState) {
                PlanState.Idle -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = requirement,
                            onValueChange = { requirement = it },
                            label = { Text("需求 / 目标") },
                            placeholder = { Text("例如：期末复习数学，目标考到 120 分") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = resources,
                            onValueChange = { resources = it },
                            label = { Text("现有资源（可空）") },
                            placeholder = { Text("例如：每天放学后 2 小时，周末全天，有复习资料") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = { showDeadlinePicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "⏰ 截止时间：" +
                                    (deadline?.let { TimeUtils.formatMillis(it) } ?: "请选择")
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("优先级", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(12.dp))
                            listOf(0 to "低", 1 to "中", 2 to "高").forEach { (p, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { priority = p }
                                ) {
                                    RadioButton(selected = priority == p, onClick = { priority = p })
                                    Text(label)
                                }
                            }
                        }
                    }
                }
                PlanState.Loading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("AI 正在分析并制定方案…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is PlanState.Success -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "📋 ${st.plan.planTitle}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (st.plan.summary.isNotBlank()) {
                            Text(
                                st.plan.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        st.plan.steps.forEachIndexed { i, step ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text("${i + 1}.", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(step.title, style = MaterialTheme.typography.bodyLarge)
                                    val isEventStep = step.type.contains("event") || step.startTime != null
                                    val detail = if (isEventStep) {
                                        "⏱ ${TimeUtils.formatMillis(step.startTime ?: step.dueTime ?: 0)}" +
                                            (if (step.remindMinutes > 0) " · 提前 ${step.remindMinutes} 分钟提醒" else "")
                                    } else {
                                        "📝 待办 · 截止 " +
                                            (step.dueTime?.let { TimeUtils.formatMillis(it) } ?: "未定") +
                                            " · ${listOf("低", "中", "高")[step.priority.coerceIn(0, 2)]}优先级"
                                    }
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
                is PlanState.Error -> {
                    Text(
                        "❌ ${st.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (val st = planState) {
                PlanState.Idle -> Button(
                    enabled = requirement.isNotBlank() && deadline != null,
                    onClick = {
                        deadline?.let { vm.generatePlan(requirement, resources, it, priority) }
                    }
                ) { Text("生成方案") }
                PlanState.Loading -> {}
                is PlanState.Success -> Button(
                    onClick = {
                        vm.applyPlan()
                        onDismiss()
                    }
                ) { Text("采纳并登记") }
                is PlanState.Error -> Button(onClick = { vm.regeneratePlan() }) { Text("重试") }
            }
        },
        dismissButton = {
            when (planState) {
                PlanState.Idle -> TextButton(onClick = onDismiss) { Text("取消") }
                PlanState.Loading -> {}
                is PlanState.Success -> Row {
                    TextButton(onClick = { vm.regeneratePlan() }) { Text("重新生成") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                is PlanState.Error -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )

    if (showDeadlinePicker) {
        DateTimePickerDialog(
            title = "设置截止时间",
            initialMillis = deadline ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000),
            onConfirm = { deadline = it; showDeadlinePicker = false },
            onDismiss = { showDeadlinePicker = false }
        )
    }
}

// ==================== 新增/编辑日程对话框 ====================

/** 日程提醒选项：分钟数 → 文案 */
private val EVENT_REMIND_OPTIONS = listOf(
    0 to "不提醒",
    10 to "提前 10 分钟",
    30 to "提前 30 分钟",
    60 to "提前 1 小时",
    120 to "提前 2 小时",
    1440 to "提前 1 天"
)

private fun remindMinutesLabel(minutes: Int): String =
    EVENT_REMIND_OPTIONS.firstOrNull { it.first == minutes }?.second ?: "提前 $minutes 分钟"

@Composable
fun AddEventDialog(
    defaultDate: LocalDate = LocalDate.now(),
    initialTitle: String = "",
    initialDescription: String = "",
    initialStart: Long? = null,
    initialEnd: Long? = null,
    initialColor: Int? = null,
    initialRemindMinutes: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (title: String, start: Long, end: Long, description: String?, color: Int, remindMinutes: Int) -> Unit
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var startTime by remember(initialStart) { mutableStateOf(initialStart) }
    var endTime by remember(initialEnd) { mutableStateOf(initialEnd) }
    var colorIndex by remember(initialColor) { mutableStateOf(EVENT_COLORS.indexOfFirst { it.value.toInt() == initialColor }.coerceAtLeast(0)) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var remindMinutes by remember(initialRemindMinutes) { mutableStateOf(initialRemindMinutes) }
    var showRemindPicker by remember { mutableStateOf(false) }

    val defaultStart = defaultDate.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val defaultEnd = defaultDate.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val start = startTime ?: defaultStart
    val end = endTime ?: defaultEnd
    val isEdit = initialTitle.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑日程" else "新建日程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("日程标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showStartPicker = true }) {
                        Text("开始 ${TimeUtils.formatMillis(start)}")
                    }
                    OutlinedButton(onClick = { showEndPicker = true }) {
                        Text("结束 ${TimeUtils.formatMillis(end)}")
                    }
                }
                OutlinedButton(
                    onClick = { showRemindPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⏰ 提醒：${remindMinutesLabel(remindMinutes)}")
                }
                Column {
                    Text("颜色", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    ColorSelector(selected = colorIndex, onSelect = { colorIndex = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && end > start) {
                        onConfirm(title.trim(), start, end, description.trim().ifBlank { null }, EVENT_COLORS[colorIndex].value.toInt(), remindMinutes)
                    }
                },
                enabled = title.isNotBlank() && end > start
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showRemindPicker) {
        AlertDialog(
            onDismissRequest = { showRemindPicker = false },
            title = { Text("提前提醒") },
            text = {
                Column {
                    EVENT_REMIND_OPTIONS.forEach { (min, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    remindMinutes = min
                                    showRemindPicker = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = remindMinutes == min,
                                onClick = {
                                    remindMinutes = min
                                    showRemindPicker = false
                                }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRemindPicker = false }) { Text("完成") }
            }
        )
    }

    if (showStartPicker) {
        DateTimePickerDialog(
            title = "设置开始时间",
            initialMillis = start,
            onConfirm = { startTime = it; showStartPicker = false },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        DateTimePickerDialog(
            title = "设置结束时间",
            initialMillis = end,
            onConfirm = { endTime = it; showEndPicker = false },
            onDismiss = { showEndPicker = false }
        )
    }
}

// ==================== 日程专注模式 ====================

/** 毫秒 → "HH:mm:ss" 倒计时文本（超过 24h 显示 "HH:MM:SS" 累加） */
private fun formatCountdown(millis: Long): String {
    val totalSec = (millis.coerceAtLeast(0) + 999) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}

/**
 * 专注模式：全屏黑底 + 自动横屏 + 大字时钟
 * 中央：当前时间（秒级刷新）+ 日程标题 + 状态倒计时
 * 左下角：日程截止时间小字；右下角：退出按钮
 * 进入时屏幕常亮（FLAG_KEEP_SCREEN_ON），退出自动恢复
 */
@Composable
private fun FocusModeScreen(
    event: CalendarEventEntity,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // 进入：横屏 + 屏幕常亮；退出：恢复竖屏 + 关闭常亮
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 秒级刷新当前时间
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val start = event.startTime
    val end = event.endTime
    val title = event.title

    // 状态：距开始 / 进行中 / 已结束
    val (statusText, statusColor) = when {
        now < start -> "距开始 ${formatCountdown(start - now)}" to Color(0xFF4FC3F7)
        now in start until end -> "进行中 · 剩余 ${formatCountdown(end - now)}" to Color(0xFF66BB6A)
        else -> "已结束" to Color(0xFFB0BEC5)
    }

    val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    val dateFmt = DateTimeFormatter.ofPattern("M月d日 EEEE")
    val localNow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D12)),
            contentAlignment = Alignment.Center
        ) {
            // 中央：大字时钟 + 日程信息
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = localNow.format(timeFmt),
                    color = Color.White,
                    fontSize = 110.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = localNow.format(dateFmt),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // 左下角：日程截止时间
            Text(
                text = "截止 ${TimeUtils.formatMillis(end, "HH:mm")} · ${TimeUtils.formatMillis(start, "HH:mm")} 开始",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp)
            )

            // 右下角：退出按钮
            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(52.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "退出专注模式",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}