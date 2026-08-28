package com.magicnote.mgxd.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.magicnote.mgxd.ui.screens.AddEventDialog
import com.magicnote.mgxd.ui.screens.AddTodoDialog
import com.magicnote.mgxd.ui.screens.AiChatScreen
import com.magicnote.mgxd.ui.screens.CalendarScreen
import com.magicnote.mgxd.ui.screens.DiaryScreen
import com.magicnote.mgxd.ui.screens.EditDiaryDialog
import com.magicnote.mgxd.ui.screens.HomeScreen
import com.magicnote.mgxd.ui.screens.SettingsScreen
import com.magicnote.mgxd.ui.screens.TodoScreen
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.prefs.UserPrefs
import com.magicnote.mgxd.ui.viewmodel.AiViewModel
import com.magicnote.mgxd.ui.viewmodel.CalendarViewModel
import com.magicnote.mgxd.ui.viewmodel.DiaryViewModel
import com.magicnote.mgxd.ui.viewmodel.SettingsViewModel
import com.magicnote.mgxd.ui.viewmodel.TodoViewModel
import com.magicnote.mgxd.ui.viewmodel.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import java.time.LocalDate
import java.time.ZoneId

private data class TabItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val tabs = listOf(
    TabItem("今日", Icons.Filled.Home, Icons.Outlined.Home),
    TabItem("待办", Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle),
    TabItem("日历", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    TabItem("日记", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    TabItem("Magic AI", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    TabItem("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
fun AppNav() {
    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    // 各页面的 ViewModel（每个 tab 独立持有）
    val todoVm: TodoViewModel = appViewModel(TodoViewModel::class.java) { it.repository.let { r -> TodoViewModel(r) } }
    val calendarVm: CalendarViewModel = appViewModel(CalendarViewModel::class.java) { it.repository.let { r -> CalendarViewModel(r, context.applicationContext) } }
    val diaryVm: DiaryViewModel = appViewModel(DiaryViewModel::class.java) { it.repository.let { r -> DiaryViewModel(r, context.applicationContext) } }
    val aiVm: AiViewModel = appViewModel(AiViewModel::class.java) { it.repository.let { r -> AiViewModel(r) } }
    val settingsVm: SettingsViewModel = appViewModel(SettingsViewModel::class.java) { it.repository.let { r -> SettingsViewModel(r) } }

    // 功能模块开关：关闭的模块从底部导航与首页隐藏
    val moduleCfg by settingsVm.moduleConfig.collectAsStateWithLifecycle()
    // 开启的功能模块数量（待办/日历/日记）
    val enabledModuleCount = listOf(moduleCfg.todoEnabled, moduleCfg.calendarEnabled, moduleCfg.diaryEnabled).count { it }
    // 功能只剩 0 或 1 个时，「今日」聚合首页没有意义 → 隐藏首页入口，直接进入剩余功能
    val hideHome = enabledModuleCount <= 1
    // 保留原始索引（0今日/1待办/2日历/3日记/4AI），按开关过滤可见 tab
    val visibleTabs = tabs.filterIndexed { index, _ ->
        when (index) {
            0 -> !hideHome
            1 -> moduleCfg.todoEnabled
            2 -> moduleCfg.calendarEnabled
            3 -> moduleCfg.diaryEnabled
            else -> true
        }
    }
    // 当前 tab 被关闭/隐藏时，自动回退到第一个可见 tab
    LaunchedEffect(moduleCfg, hideHome) {
        val visibleIndices = visibleTabs.map { tabs.indexOf(it) }
        if (currentTab !in visibleIndices) {
            currentTab = visibleIndices.first()
        }
    }

    // 全局对话框状态
    var showAddTodo by remember { mutableStateOf(false) }
    var showAddEvent by remember { mutableStateOf(false) }
    var showAddDiary by remember { mutableStateOf(false) }
    // 编辑日记目标（null 表示新增）
    var editDiaryTarget by remember { mutableStateOf<DiaryEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (currentTab) {
                0 -> HomeScreen(
                    todoVm = todoVm,
                    calendarVm = calendarVm,
                    diaryVm = diaryVm,
                    moduleConfig = moduleCfg,
                    onNavigateTo = { currentTab = it },
                    onOpenSettings = { currentTab = 5 },
                    onAddTodo = { showAddTodo = true },
                    onAddEvent = { showAddEvent = true },
                    onAddDiary = { showAddDiary = true }
                )
                1 -> TodoScreen(vm = todoVm, onAddClick = { showAddTodo = true })
                2 -> CalendarScreen(vm = calendarVm, countdowns = todoVm.countdowns, onAddClick = { showAddEvent = true })
                3 -> DiaryScreen(
                    vm = diaryVm,
                    onAddClick = { showAddDiary = true },
                    onEditClick = { diary ->
                        editDiaryTarget = diary
                        showAddDiary = true
                    }
                )
                4 -> AiChatScreen(vm = aiVm)
                5 -> SettingsScreen(
                    vm = settingsVm,
                    onClose = {
                        // 关闭设置：回到第一个非设置的功能页
                        currentTab = visibleTabs.firstOrNull { tabs.indexOf(it) != 5 }?.let { tabs.indexOf(it) } ?: 4
                    }
                )
            }
        }
        NavigationBar {
            visibleTabs.forEachIndexed { _, tab ->
                val index = tabs.indexOf(tab)
                NavigationBarItem(
                    selected = currentTab == index,
                    onClick = { currentTab = index },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == index) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.label
                        )
                    },
                    label = { Text(tab.label) }
                )
            }
        }
    }

    // ===== 全局对话框 =====
    if (showAddTodo) {
        AddTodoDialog(
            onDismiss = { showAddTodo = false },
            onConfirm = { title, desc, due, remind, priority, isLongTerm ->
                todoVm.addTodo(context, title, desc, due, remind, priority, isLongTerm)
                showAddTodo = false
            }
        )
    }
    if (showAddEvent) {
        AddEventDialog(
            defaultDate = calendarVm.selectedDate.value,
            onDismiss = { showAddEvent = false },
            onConfirm = { title, start, end, desc, color, remindMinutes ->
                calendarVm.addEvent(title, start, end, desc, color, remindMinutes)
                showAddEvent = false
            }
        )
    }
    if (showAddDiary) {
        val today = LocalDate.now()
        val dayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        EditDiaryDialog(
            defaultDate = today,
            existing = editDiaryTarget,
            onDismiss = {
                showAddDiary = false
                editDiaryTarget = null
            },
            onConfirm = { title, content, mood, imagePaths ->
                // 编辑时沿用原日记的日期；新增用今天
                diaryVm.saveDiary(editDiaryTarget?.date ?: dayStart, editDiaryTarget, title, content, mood, imagePaths)
                showAddDiary = false
                editDiaryTarget = null
            }
        )
    }
}