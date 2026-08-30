package com.magicnote.mgxd.ui.screens

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.magicnote.mgxd.ai.Personality
import com.magicnote.mgxd.data.prefs.UserPrefs
import com.magicnote.mgxd.screentime.ScreenTimeManager
import com.magicnote.mgxd.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onClose: () -> Unit = {}) {
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val notifyConfig by vm.notifyConfig.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var baseUrl by remember { mutableStateOf(aiConfig.baseUrl) }
    var apiKey by remember { mutableStateOf(aiConfig.apiKey) }
    var model by remember { mutableStateOf(aiConfig.model) }
    var personalityId by remember { mutableStateOf(aiConfig.personalityId) }
    var customPrompt by remember { mutableStateOf(aiConfig.customPrompt) }
    var summaryEnabled by remember { mutableStateOf(notifyConfig.dailySummaryEnabled) }
    var notifEnabled by remember { mutableStateOf(notifyConfig.notificationEnabled) }
    var summaryHour by remember { mutableStateOf(notifyConfig.dailySummaryHour) }
    var summaryMinute by remember { mutableStateOf(notifyConfig.dailySummaryMinute) }

    // 同步外部变化（仅首次加载时同步一次，避免后续 DataStore 发射覆盖用户正在输入的内容）
    var aiSynced by remember { mutableStateOf(false) }
    var notifySynced by remember { mutableStateOf(false) }
    LaunchedEffect(aiConfig) {
        if (!aiSynced) {
            baseUrl = aiConfig.baseUrl
            apiKey = aiConfig.apiKey
            model = aiConfig.model
            personalityId = aiConfig.personalityId
            customPrompt = aiConfig.customPrompt
            aiSynced = true
        }
    }
    LaunchedEffect(notifyConfig) {
        if (!notifySynced) {
            summaryEnabled = notifyConfig.dailySummaryEnabled
            notifEnabled = notifyConfig.notificationEnabled
            summaryHour = notifyConfig.dailySummaryHour
            summaryMinute = notifyConfig.dailySummaryMinute
            notifySynced = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ===== AI 配置 =====
            SectionCard(title = "Magic AI 接口配置", icon = Icons.Default.Key) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API Base URL") },
                    placeholder = { Text(UserPrefs.DEFAULT_BASE_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名称") },
                    placeholder = { Text(UserPrefs.DEFAULT_MODEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 模型支持图片识别：开启后注入日记时连同日记图片一起识别
                val modelVision by vm.modelVision.collectAsStateWithLifecycle()
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("模型支持图片识别", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "开启后：AI 回复日记时会把日记附带的图片一起注入识别（需模型支持视觉，如 gpt-4o / qwen-vl）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = modelVision,
                        onCheckedChange = { vm.saveModelVision(it) }
                    )
                }
                Text(
                    "支持 OpenAI 及所有兼容接口（DeepSeek / Kimi / 通义 / 本地 Ollama 等）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedButton(
                    onClick = {
                        vm.saveAiConfig(baseUrl, apiKey, model, personalityId, customPrompt)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存 Magic AI 配置") }
            }

            // ===== 性格 =====
            SectionCard(title = "Magic AI 性格", icon = Icons.Default.Person) {
                Personality.entries.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = personalityId == p.id,
                                onClick = { personalityId = p.id }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = personalityId == p.id,
                            onClick = { personalityId = p.id }
                        )
                        Column {
                            Text("${p.emoji} ${p.label}", style = MaterialTheme.typography.titleMedium)
                            Text(p.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    label = { Text("自定义性格 Prompt（选填，覆盖预设）") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { vm.saveAiConfig(baseUrl, apiKey, model, personalityId, customPrompt) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存性格设置") }
            }

            // ===== 通知 =====
            SectionCard(title = "通知与提醒", icon = Icons.Default.Notifications) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("启用通知", style = MaterialTheme.typography.titleMedium)
                        Text("待办提醒 + 每日汇总", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = notifEnabled,
                        onCheckedChange = {
                            notifEnabled = it
                            vm.saveNotifyConfig(context, summaryEnabled, summaryHour, summaryMinute, it)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("每日进度汇总", style = MaterialTheme.typography.titleMedium)
                        Text("定时统计完成进度并催促", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = summaryEnabled,
                        onCheckedChange = {
                            summaryEnabled = it
                            vm.saveNotifyConfig(context, it, summaryHour, summaryMinute, notifEnabled)
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("汇总时间", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val h = (summaryHour + 1) % 24
                        summaryHour = h
                        vm.saveNotifyConfig(context, summaryEnabled, h, summaryMinute, notifEnabled)
                    }) { Text("−") }
                    Text(
                        text = String.format("%02d:%02d", summaryHour, summaryMinute),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    OutlinedButton(onClick = {
                        val h = (summaryHour + 23) % 24
                        summaryHour = h
                        vm.saveNotifyConfig(context, summaryEnabled, h, summaryMinute, notifEnabled)
                    }) { Text("+") }
                }
                Text(
                    "每天到点会收到一条通知，按你选的性格口吻汇报进度并催促完成",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                // 系统权限引导：通知权限（Android 13+）与精确闹钟权限（Android 12+）不足时提示，避免收不到每日汇总
                val notifManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val needNotifPerm = Build.VERSION.SDK_INT >= 33 && !notifManager.areNotificationsEnabled()
                val needExactAlarm = Build.VERSION.SDK_INT >= 31 &&
                    !(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
                if (needNotifPerm) {
                    Text(
                        "⚠️ 系统通知权限未开启，收不到每日汇总和待办提醒",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("去开启通知权限") }
                }
                if (needExactAlarm) {
                    Text(
                        "⚠️ 未授予「闹钟和提醒」权限，汇总可能被系统延迟。授予后每天准点发送",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("去授予精确闹钟权限") }
                }
            }

            // ===== 功能模块开关 =====
            SectionCard(title = "功能模块", icon = Icons.Default.Checklist) {
                val moduleCfg by vm.moduleConfig.collectAsStateWithLifecycle()
                ModuleSwitchRow(
                    title = "待办",
                    desc = "关闭后隐藏底部导航与首页的待办入口/卡片",
                    checked = moduleCfg.todoEnabled,
                    onCheckedChange = { vm.saveModuleConfig(it, moduleCfg.calendarEnabled, moduleCfg.diaryEnabled) }
                )
                ModuleSwitchRow(
                    title = "日历",
                    desc = "关闭后隐藏底部导航与首页的日历入口/卡片",
                    checked = moduleCfg.calendarEnabled,
                    onCheckedChange = { vm.saveModuleConfig(moduleCfg.todoEnabled, it, moduleCfg.diaryEnabled) }
                )
                ModuleSwitchRow(
                    title = "日记",
                    desc = "关闭后隐藏底部导航与首页的日记入口/卡片",
                    checked = moduleCfg.diaryEnabled,
                    onCheckedChange = { vm.saveModuleConfig(moduleCfg.todoEnabled, moduleCfg.calendarEnabled, it) }
                )
                Text(
                    "关闭模块不会删除已有数据，只是从界面隐藏；重新开启即可恢复",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // ===== 日记自动回复 =====
            SectionCard(title = "日记自动回复", icon = Icons.Default.AutoAwesome) {
                val diaryAutoReply by vm.diaryAutoReply.collectAsStateWithLifecycle()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("日记自动回复", style = MaterialTheme.typography.titleMedium)
                        Text("每次写完日记，Magic AI 自动回复（共情/建议/鼓励）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = diaryAutoReply,
                        onCheckedChange = { vm.saveDiaryAutoReply(it) }
                    )
                }
                Text(
                    "开启后：保存日记时 AI 会读一遍内容，以当前人格口吻回复，回复存入 Magic AI 聊天记录并推送通知。需要先在「Magic AI 接口配置」填好 API Key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // ===== 纯净模式 =====
            SectionCard(title = "纯净模式", icon = Icons.Default.PowerSettingsNew) {
                val pureMode by vm.pureMode.collectAsStateWithLifecycle()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("纯净模式", style = MaterialTheme.typography.titleMedium)
                        Text("关闭后台保活与所有提醒", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = pureMode,
                        onCheckedChange = { vm.setPureMode(context, it) }
                    )
                }
                Text(
                    "开启后：停止后台守护服务，关闭屏幕监控、待办提醒、每日汇总、每日报告、每日清理等所有后台功能，应用完全静默、不占资源。需要提醒时再关掉此开关即可全部恢复。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // ===== 屏幕时间 =====
            SectionCard(title = "屏幕时间", icon = Icons.Default.Timer) {
                val stCfg by vm.screenTimeConfig.collectAsStateWithLifecycle()
                val stats by vm.screenTimeStats.collectAsStateWithLifecycle()
                var usageGranted by remember { mutableStateOf(ScreenTimeManager.hasUsageAccess(context)) }

                LaunchedEffect(Unit) { vm.refreshScreenTimeStats(context) }

                if (!usageGranted) {
                    Text(
                        "需要开启「使用情况访问权限」才能统计手机和应用使用时长（系统特殊权限，仅本机统计，不上传）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedButton(
                        onClick = {
                            ScreenTimeManager.openUsageAccessSettings(context)
                            usageGranted = ScreenTimeManager.hasUsageAccess(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("去开启使用情况访问权限") }
                    OutlinedButton(
                        onClick = {
                            usageGranted = ScreenTimeManager.hasUsageAccess(context)
                            vm.refreshScreenTimeStats(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("我已完成授权，刷新统计") }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("今日手机使用时长", style = MaterialTheme.typography.titleMedium)
                            Text("屏幕时间统计（仅本机）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(
                            formatDuration(stats?.todayUsageMillis),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text("今日应用使用排行", style = MaterialTheme.typography.labelMedium)
                    val apps = stats?.apps.orEmpty()
                    if (apps.isEmpty()) {
                        Text(
                            "暂无使用数据，过一会儿再来看看吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        apps.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    formatDuration(app.millis),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("娱乐超时提醒", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "连续使用娱乐应用超过阈值自动提醒（可 AI 生成文案）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = stCfg.enabled,
                        onCheckedChange = { vm.saveScreenTimeConfig(context, it, stCfg.thresholdMinutes) }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒阈值", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        vm.saveScreenTimeConfig(context, stCfg.enabled, (stCfg.thresholdMinutes - 15).coerceAtLeast(15))
                    }) { Text("−") }
                    Text(
                        text = "${stCfg.thresholdMinutes} 分钟",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    OutlinedButton(onClick = {
                        vm.saveScreenTimeConfig(context, stCfg.enabled, (stCfg.thresholdMinutes + 15).coerceAtMost(180))
                    }) { Text("+") }
                }
                // 分类管理
                val categoryOverrides by vm.categoryOverrides.collectAsStateWithLifecycle()
                var showCategoryDialog by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showCategoryDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("管理应用分类") }
                Text(
                    "内置自动分类：娱乐 / 社交 / 购物 / 工具 / 学习 / 其他 · 每日 22:00 日报告 / 周日 08:00 周报告",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                if (showCategoryDialog) {
                    CategoryManageDialog(
                        overrides = categoryOverrides,
                        onSave = { pkg, cat -> vm.saveCategoryOverride(pkg, cat) },
                        onRemove = { pkg -> vm.removeCategoryOverride(pkg) },
                        onDismiss = { showCategoryDialog = false }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== 版本号 =====
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "3.1"
            Text(
                "Magic note v$versionName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
            )

            // ===== 署名 =====
            Text(
                "Magic note v${com.magicnote.mgxd.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
            )
            Text(
                "design by MGXD(and DeepSeek)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

/** 功能模块开关行（标题 + 说明 + Switch） */
@Composable
private fun ModuleSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 毫秒 → 「x小时y分钟」展示 */
private fun formatDuration(millis: Long?): String {
    if (millis == null || millis <= 0L) return "0 分钟"
    val totalMin = millis / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
}

/** 应用自定义分类管理对话框 */
@Composable
private fun CategoryManageDialog(
    overrides: Map<String, String>,
    onSave: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pkgInput by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf(ScreenTimeManager.AppCategory.ENTERTAINMENT) }
    var catMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义应用分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "把应用包名归入指定类别（会覆盖内置规则），未设置的应用按内置规则自动分类。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (overrides.isEmpty()) {
                    Text(
                        "还没有自定义分类，在下方添加吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    overrides.forEach { (pkg, cat) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pkg,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                ScreenTimeManager.AppCategory.fromId(cat)?.label ?: cat,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { onRemove(pkg) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = pkgInput,
                    onValueChange = { pkgInput = it },
                    label = { Text("应用包名，如 com.tencent.mm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { catMenu = true }) {
                            Text("类别：${selectedCat.label}")
                        }
                        DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                            ScreenTimeManager.AppCategory.entries.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.label) },
                                    onClick = {
                                        selectedCat = cat
                                        catMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (pkgInput.isNotBlank()) {
                            onSave(pkgInput.trim(), selectedCat.name)
                            pkgInput = ""
                        }
                    }) { Text("添加") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}