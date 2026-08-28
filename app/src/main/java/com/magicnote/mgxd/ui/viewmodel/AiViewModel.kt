package com.magicnote.mgxd.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.ai.Personality
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.data.prefs.UserPrefs
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.notify.ReminderScheduler
import com.magicnote.mgxd.screentime.ScreenTimeManager
import com.magicnote.mgxd.util.EventConflictResolver
import com.magicnote.mgxd.util.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

class AiViewModel(private val repo: AppRepository) : ViewModel() {

    /** 聊天消息（带时间戳，持久化自 Room） */
    data class ChatItem(
        val role: String,          // user / assistant
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val client = AiClient()

    init {
        // 聊天历史持久化：Room 流驱动 UI，重启后自动恢复
        viewModelScope.launch {
            repo.observeChats().collect { list ->
                _messages.value = list.map { ChatItem(it.role, it.content, it.timestamp) }
            }
        }
    }

    fun sendMessage(context: Context, text: String) {
        if (text.isBlank() || _loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                // 用户消息先持久化
                repo.insertChat("user", text)

                val config = repo.aiConfig.collectFirst()
                if (config.apiKey.isBlank()) {
                    repo.insertChat(
                        "assistant",
                        "😅 还没配置 API Key 哦！请到「设置」页填上 API Key（支持 OpenAI 及各类兼容接口）。"
                    )
                    return@launch
                }
                // 注入用户生活上下文（待办 / 日程 / 日记 / 屏幕时间）
                // 关闭功能的数据不注入 AI 详情，但 AiPrompter 会注入 hidden 摘要（历史存在），
                // 避免 AI 误以为「从未记录过」；指令解析则只给开启功能的数据
                val moduleCfg = repo.moduleConfig.collectFirst()
                val allTodos = repo.observeTodos().collectFirst()
                val allEvents = repo.observeAllEvents().collectFirst()
                val allDiaries = repo.observeDiaries().collectFirst()
                val cmdTodos = if (moduleCfg.todoEnabled) allTodos else emptyList()
                val cmdEvents = if (moduleCfg.calendarEnabled) allEvents else emptyList()

                // 功能 E：先尝试把输入解析为「日程/待办增删改」指令，命中则直接执行并回复，不再走普通聊天
                if (looksLikeCommand(text) && tryExecuteCommand(context, text, config, cmdTodos, cmdEvents, moduleCfg)) {
                    return@launch
                }

                val screenTimeSummary = buildScreenTimeSummary(context)

                val personality = Personality.fromId(config.personalityId)
                val systemPrompt = AiPrompter.buildSystemPrompt(
                    personality = personality,
                    customPrompt = config.customPrompt,
                    todos = allTodos,
                    events = allEvents,
                    recentDiaries = allDiaries,
                    screenTimeSummary = screenTimeSummary,
                    todoEnabled = moduleCfg.todoEnabled,
                    calendarEnabled = moduleCfg.calendarEnabled,
                    diaryEnabled = moduleCfg.diaryEnabled
                )

                // 历史上下文（Room 流异步更新，这里手动拼上刚发的消息，确保 AI 看得到）
                val history = (_messages.value + ChatItem("user", text)).takeLast(12).map {
                    AiClient.ChatMessage(role = it.role, content = it.content)
                }
                val allMessages = listOf(
                    AiClient.ChatMessage("system", systemPrompt)
                ) + history

                val reply = withTimeoutOrNull(60_000) {
                    client.chat(
                        baseUrl = config.baseUrl,
                        apiKey = config.apiKey,
                        model = config.model,
                        messages = allMessages
                    )
                }
                // 超时兜底：不挂起聊天界面
                if (reply == null) {
                    repo.insertChat("assistant", "😅 请求超时了，请稍后再试一次。")
                    return@launch
                }
                // AI 回复持久化
                repo.insertChat("assistant", reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.insertChat("assistant", "😅 出错了：${e.message ?: "未知错误"}")
            } finally {
                _loading.value = false
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch { repo.clearChats() }
    }

    /** 采集今日屏幕时间摘要（未授权或异常返回 null，AI 上下文标记为无数据） */
    private suspend fun buildScreenTimeSummary(context: Context): String? =
        withContext(Dispatchers.IO) {
            if (!ScreenTimeManager.hasUsageAccess(context)) return@withContext null
            try {
                val overrides = repo.categoryOverrides.first()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val start = cal.timeInMillis
                val total = ScreenTimeManager.getTodayUsageMillis(context)
                val cats = ScreenTimeManager.getCategoryUsage(
                    context, start, System.currentTimeMillis(), overrides
                )
                val apps = ScreenTimeManager.getTodayAppUsages(context, limit = 5)
                buildString {
                    append("今日总时长：").append(TimeUtils.formatDuration(total))
                    if (cats.isNotEmpty()) {
                        append("；分类：")
                        append(
                            cats.joinToString("、") { c ->
                                "${c.category.label} ${c.millis * 100 / total.coerceAtLeast(1)}%"
                            }
                        )
                    }
                    if (apps.isNotEmpty()) {
                        append("；使用最多：")
                        append(apps.joinToString("、") { a -> "${a.label} ${TimeUtils.formatDuration(a.millis)}" })
                    }
                }.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    // ==================== 功能 E：AI 操作日程/待办（增删改） ====================

    /** AI 解析出的操作指令（单个） */
    private data class ParsedCommand(
        val action: String,
        val targetId: Long = 0L,
        val title: String? = null,
        val description: String? = null,
        val dueTime: Long? = null,
        val remindAt: Long? = null,
        val priority: Int = 1,
        val isLongTerm: Boolean = false,
        val startTime: Long? = null,
        val endTime: Long? = null,
        val color: Int = 0,
        val targetDate: String? = null,   // 倒数日目标日期 "yyyy-MM-dd"
        val remindTime: String? = null,   // 打卡每日提醒 "HH:mm"
        val targetDays: Int? = null       // 打卡目标天数（0=不限）
    )

    /** AI 解析出的一批操作（一句话可提炼多个日程/待办） */
    private data class ParsedCommandBatch(
        val actions: List<ParsedCommand>,
        val summary: String
    )

    private val cmdJson = Json { ignoreUnknownKeys = true }

    /** 本地快速预判：包含操作类关键词才调 AI 解析（省一次 API 调用） */
    private fun looksLikeCommand(text: String): Boolean {
        val t = text.trim()
        if (t.length > 120) return false
        return COMMAND_KEYWORDS.any { t.contains(it) }
    }

    /** 尝试把输入作为指令执行；成功返回 true（已回复用户），普通聊天返回 false */
    private suspend fun tryExecuteCommand(
        context: Context,
        text: String,
        config: UserPrefs.AiConfig,
        todos: List<TodoEntity>,
        events: List<CalendarEventEntity>,
        moduleCfg: UserPrefs.ModuleConfig = UserPrefs.ModuleConfig()
    ): Boolean {
        val prompt = AiPrompter.buildCommandParsePrompt(
            text, LocalDateTime.now(),
            todos, events,
            repo.observeCountdowns().collectFirst(),
            repo.observeHabits().collectFirst(),
            moduleCfg.todoEnabled, moduleCfg.calendarEnabled
        )
        val reply = try {
            client.chat(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = listOf(AiClient.ChatMessage("user", prompt))
                // 不启用 response_format：部分兼容 API 不支持 json_mode 会直接 400，导致解析链路整体失败
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        val cmd = parseCommandJson(reply) ?: return false
        // 已关闭功能的指令直接剔除（用户会走普通聊天，AI 依据 module 状态提示「功能已关闭」）
        val allowed = cmd.actions.filter { c ->
            val a = normalizeAction(c.action)
            when {
                a.contains("todo") -> moduleCfg.todoEnabled
                a.contains("event") -> moduleCfg.calendarEnabled
                else -> true
            }
        }
        if (allowed.isEmpty()) return false

        // 逐条执行（一条消息可提炼多个日程/待办）
        var executed = 0
        for (c in allowed) {
            if (executeCommand(context, c, todos, events, text)) executed++
        }
        if (executed > 0) {
            repo.insertChat("assistant", cmd.summary)
            return true
        }
        return false
    }

    /** 执行解析出的指令（增删改待办/日程） */
    private suspend fun executeCommand(
        context: Context,
        cmd: ParsedCommand,
        todos: List<TodoEntity>,
        events: List<CalendarEventEntity>,
        userText: String = ""
    ): Boolean = try {
        // action 名称模糊归一化 + 用户话术兜底（纠正 AI 类型误判）
        val rawAction = normalizeAction(cmd.action)
        val t = userText
        val wantsTodo = t.contains("待办") || t.contains("任务") || t.contains("事项") ||
            t.contains("记得") || t.contains("别忘了")
        val wantsEvent = t.contains("日程") || t.contains("日历") || t.contains("会议") ||
            t.contains("预约") || t.contains("几点") || t.contains("什么时候")
        val action = when {
            // 明确要日程，或带了起止时间，或给了截止日期且没说要待办 → 纠正为日程
            rawAction == "create_todo" && (cmd.startTime != null || cmd.endTime != null || wantsEvent ||
                (cmd.dueTime != null && !wantsTodo)) -> "create_event"
            // 明确要待办/任务/事项 → 纠正为待办
            rawAction == "create_event" && wantsTodo -> "create_todo"
            else -> rawAction
        }
        when (action) {
            "create_habit" -> {
                // 每日打卡：标题 + 可选提醒时间(HH:mm) + 可选目标天数
                val title = cmd.title?.takeIf { it.isNotBlank() } ?: return false
                val (h, m) = cmd.remindTime?.let { parseTime(it) } ?: (-1 to 0)
                val targetDays = cmd.targetDays ?: 0
                val id = repo.insertHabit(
                    HabitEntity(title = title, remindHour = h, remindMinute = m, targetDays = targetDays)
                )
                if (h >= 0) {
                    repo.getHabit(id)?.let { ReminderScheduler.scheduleHabitReminder(context, it) }
                }
                true
            }
            "create_countdown" -> {
                // 倒数日：标题 + 目标日期(yyyy-MM-dd)
                val title = cmd.title?.takeIf { it.isNotBlank() } ?: return false
                val date = cmd.targetDate?.let { parseDateToDayStart(it) } ?: return false
                repo.insertCountdown(CountdownEntity(title = title, targetDate = date))
                true
            }
            "create_todo" -> {
                val title = cmd.title?.takeIf { it.isNotBlank() } ?: return false
                val id = repo.insertTodo(
                TodoEntity(
                    title = title,
                    description = cmd.description?.takeIf { it.isNotBlank() },
                    dueTime = cmd.dueTime,
                    remindAt = cmd.remindAt,
                    priority = cmd.priority.coerceIn(0, 2),
                    isLongTerm = cmd.isLongTerm,
                    source = SOURCE_AI
                )
            )
                // 今日待办且设置了未来提醒才调度
                if (!cmd.isLongTerm && cmd.remindAt != null && cmd.remindAt > System.currentTimeMillis()) {
                    repo.getTodo(id)?.let { saved ->
                        ReminderScheduler.scheduleTodoReminder(context, saved)
                        repo.updateTodo(saved.copy(remindScheduled = true))
                    }
                }
                true
            }
            "edit_todo" -> {
                val target = todos.firstOrNull { it.id == cmd.targetId } ?: return false
                val title = cmd.title?.takeIf { it.isNotBlank() } ?: target.title
                val remindAt = cmd.remindAt ?: target.remindAt
                val dueTime = cmd.dueTime ?: target.dueTime
                ReminderScheduler.cancelTodoReminder(context, target.id)
                val updated = target.copy(
                    title = title,
                    description = cmd.description?.takeIf { it.isNotBlank() } ?: target.description,
                    dueTime = if (target.isLongTerm) null else dueTime,
                    remindAt = if (target.isLongTerm) null else remindAt,
                    priority = cmd.priority.coerceIn(0, 2),
                    remindScheduled = false
                )
                repo.updateTodo(updated)
                if (!updated.isLongTerm && updated.remindAt != null && updated.remindAt > System.currentTimeMillis()) {
                    ReminderScheduler.scheduleTodoReminder(context, updated)
                    repo.updateTodo(updated.copy(remindScheduled = true))
                }
                true
            }
            "delete_todo" -> {
                val target = todos.firstOrNull { it.id == cmd.targetId } ?: return false
                ReminderScheduler.cancelTodoReminder(context, target.id)
                repo.deleteTodoById(target.id)
                true
            }
            "create_event" -> {
                val title = cmd.title?.takeIf { it.isNotBlank() } ?: return false
                // 若 AI 只给了截止时间（从待办纠正而来），用 dueTime 作为日程开始，避免落到当前时刻
                val start = cmd.startTime ?: cmd.dueTime ?: System.currentTimeMillis()
                val end = cmd.endTime ?: (start + 60 * 60 * 1000)
                // 时间冲突自动对齐：后一个日程顺延
                val (s, e) = EventConflictResolver.resolve(start, end, events)
                repo.insertEvent(
        CalendarEventEntity(
            title = title,
            startTime = s,
            endTime = e,
            description = cmd.description?.takeIf { it.isNotBlank() },
            color = cmd.color.takeIf { it != 0 } ?: 0xFF7C4DFF.toInt(),
            source = SOURCE_AI
        )
    )
                true
            }
            "edit_event" -> {
                val target = events.firstOrNull { it.id == cmd.targetId } ?: return false
                val start = cmd.startTime ?: target.startTime
                val end = cmd.endTime ?: (cmd.startTime?.plus(60 * 60 * 1000) ?: target.endTime)
                val (s, e) = EventConflictResolver.resolve(start, end, events, target.id)
                repo.updateEvent(
                    target.copy(
                        title = cmd.title?.takeIf { it.isNotBlank() } ?: target.title,
                        description = cmd.description?.takeIf { it.isNotBlank() } ?: target.description,
                        startTime = s,
                        endTime = e,
                        color = cmd.color.takeIf { it != 0 } ?: target.color
                    )
                )
                true
            }
            "delete_event" -> {
                if (events.none { it.id == cmd.targetId }) return false
                repo.deleteEventById(cmd.targetId)
                true
            }
            else -> false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** 解析 AI 返回的命令 JSON（支持 actions 数组，兼容旧的单对象格式；整体解析失败时再容错提取 actions 片段） */
    private fun parseCommandJson(raw: String): ParsedCommandBatch? {
        directParse(raw)?.let { return it }
        // 容错：AI 可能输出围栏/多余文字/解释，只提取 actions 数组片段再解析
        val actionsJson = Regex("\"actions\"\\s*:\\s*\\[.*?]").find(raw)?.value ?: return null
        return try {
            val arr = cmdJson.parseToJsonElement(actionsJson).jsonArray
            val actions = arr.mapNotNull { (it as? JsonObject)?.let { el -> parseAction(el) } }
            if (actions.isEmpty()) null else ParsedCommandBatch(actions, "已完成")
        } catch (e: Exception) {
            null
        }
    }

    private fun directParse(raw: String): ParsedCommandBatch? {
        val jsonStr = raw
            .substringAfter("{")
            .substringBeforeLast("}")
            .let { "{$it}" }
        return try {
            val obj = cmdJson.parseToJsonElement(jsonStr).jsonObject
            val summary = obj.str("summary") ?: "已完成"
            val actions = (obj["actions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let { el -> parseAction(el) } }
                ?: emptyList()
            if (actions.isNotEmpty()) return ParsedCommandBatch(actions, summary)
            // 兼容旧格式：顶层直接是单个 action 对象
            parseAction(obj)?.let { ParsedCommandBatch(listOf(it), summary) }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAction(obj: JsonObject): ParsedCommand? {
        val action = obj.str("action") ?: return null
        if (action == "none") return null
        return ParsedCommand(
            action = action,
            targetId = obj.str("targetId")?.toLongOrNull() ?: 0L,
            title = obj.str("title"),
            description = obj.str("description"),
            dueTime = obj.str("dueTime")?.let { parseLocalDateTime(it) },
            remindAt = obj.str("remindAt")?.let { parseLocalDateTime(it) },
            priority = obj.str("priority")?.toIntOrNull() ?: 1,
            isLongTerm = obj.str("isLongTerm")?.toBoolean() ?: false,
            startTime = obj.str("startTime")?.let { parseLocalDateTime(it) },
            endTime = obj.str("endTime")?.let { parseLocalDateTime(it) },
            color = obj.str("color")?.toIntOrNull() ?: 0,
            targetDate = obj.str("targetDate"),
            remindTime = obj.str("remindTime"),
            targetDays = obj.str("targetDays")?.toIntOrNull()
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { if (it is JsonPrimitive) it.content else null }

    /** 把 AI 返回的各种 action 名称归一化为标准动作（容错增强） */
    private fun normalizeAction(a: String): String {
        val s = a.lowercase()
        val isCreate = s.contains("create") || s.contains("add") || s.contains("new") ||
            s.contains("创建") || s.contains("新增") || s.contains("建")
        val isEdit = s.contains("edit") || s.contains("update") || s.contains("change") ||
            s.contains("modify") || s.contains("修改") || s.contains("改")
        val isDelete = s.contains("delete") || s.contains("remove") || s.contains("cancel") ||
            s.contains("删除") || s.contains("取消") || s.contains("删")
        val isEvent = s.contains("event") || s.contains("calendar") || s.contains("schedule") ||
            s.contains("日程") || s.contains("日历") || s.contains("会议")
        val isTodo = s.contains("todo") || s.contains("task") || s.contains("待办") || s.contains("任务")
        val isHabit = s.contains("habit") || s.contains("打卡") || s.contains("习惯")
        val isCountdown = s.contains("countdown") || s.contains("倒计时") || s.contains("倒数")
        return when {
            isCreate && isHabit -> "create_habit"
            isCreate && isCountdown -> "create_countdown"
            isCreate && isEvent -> "create_event"
            isCreate && isTodo -> "create_todo"
            isEdit && isEvent -> "edit_event"
            isEdit && isTodo -> "edit_todo"
            isDelete && isEvent -> "delete_event"
            isDelete && isTodo -> "delete_todo"
            else -> a
        }
    }

    private fun parseLocalDateTime(s: String): Long? = try {
        LocalDateTime.parse(s.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: Exception) {
        null
    }

    /** 解析 "yyyy-MM-dd" → 当天 0 点毫秒（倒数日目标日期） */
    private fun parseDateToDayStart(s: String): Long? = try {
        LocalDate.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: Exception) {
        null
    }

    /** 解析 "HH:mm" → (小时, 分钟)，非法返回 null */
    private fun parseTime(s: String): Pair<Int, Int>? = try {
        val parts = s.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) null else h to m
    } catch (e: Exception) {
        null
    }

    companion object {
        /** AI 创建来源标记（列表页显示「由 magic ai 创建」） */
        const val SOURCE_AI = "magic_ai"

        /** 指令关键词（本地预过滤，命中才调 AI 解析） */
        private val COMMAND_KEYWORDS = listOf(
            "建", "加", "新增", "创建", "记", "记录", "记上", "帮我", "记一个", "安排", "安排下", "记一下",
            "改", "编辑", "修改", "更新", "推迟", "提前", "移到", "调到", "挪到",
            "删", "取消", "移除", "去掉", "清掉",
            "待办", "任务", "日程", "会议", "预约", "提醒",
            "打卡", "习惯", "坚持", "每天",
            "倒数", "倒计时", "纪念", "生日", "高考", "距离",
            "去", "见", "约", "要", "记得", "别忘了", "下周一", "下周", "周末"
        )
    }
}