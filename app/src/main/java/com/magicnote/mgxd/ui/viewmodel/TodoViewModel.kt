package com.magicnote.mgxd.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.notify.ReminderScheduler
import com.magicnote.mgxd.util.HabitEncouragement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TodoViewModel(private val repo: AppRepository) : ViewModel() {

    private val _todos = MutableStateFlow<List<TodoEntity>>(emptyList())
    val todos: StateFlow<List<TodoEntity>> = _todos.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    // ================= 每日打卡 =================
    private val _habits = MutableStateFlow<List<HabitEntity>>(emptyList())
    val habits: StateFlow<List<HabitEntity>> = _habits.asStateFlow()

    /** 打卡成功后的鼓励（habit 更新后的实体 + 文案），UI 弹窗展示后调用 dismissCheckIn 清除 */
    private val _lastCheckIn = MutableStateFlow<Pair<HabitEntity, String>?>(null)
    val lastCheckIn: StateFlow<Pair<HabitEntity, String>?> = _lastCheckIn.asStateFlow()

    // ================= 倒数日 =================
    private val _countdowns = MutableStateFlow<List<CountdownEntity>>(emptyList())
    val countdowns: StateFlow<List<CountdownEntity>> = _countdowns.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeTodos().collect { _todos.value = it }
        }
        viewModelScope.launch {
            repo.observeActiveTodos().collect { _activeCount.value = it.size }
        }
        viewModelScope.launch {
            repo.observeHabits().collect { _habits.value = it }
        }
        viewModelScope.launch {
            repo.observeCountdowns().collect { _countdowns.value = it }
        }
    }

    // ================= 每日打卡操作 =================

    /** 创建打卡习惯（remindHour = -1 表示不提醒） */
    fun addHabit(context: Context, title: String, remindHour: Int, remindMinute: Int, targetDays: Int) {
        viewModelScope.launch {
            val id = repo.insertHabit(
                HabitEntity(title = title, remindHour = remindHour, remindMinute = remindMinute, targetDays = targetDays)
            )
            if (remindHour >= 0) {
                repo.getHabit(id)?.let { ReminderScheduler.scheduleHabitReminder(context, it) }
            }
        }
    }

    /** 编辑打卡习惯（改标题/提醒时间/目标天数，打卡记录保留） */
    fun editHabit(context: Context, habit: HabitEntity, title: String, remindHour: Int, remindMinute: Int, targetDays: Int) {
        viewModelScope.launch {
            ReminderScheduler.cancelHabitReminder(context, habit.id)
            val updated = habit.copy(title = title, remindHour = remindHour, remindMinute = remindMinute, targetDays = targetDays)
            repo.updateHabit(updated)
            if (remindHour >= 0) ReminderScheduler.scheduleHabitReminder(context, updated)
        }
    }

    /** 删除打卡习惯 */
    fun deleteHabit(context: Context, habit: HabitEntity) {
        viewModelScope.launch {
            ReminderScheduler.cancelHabitReminder(context, habit.id)
            repo.deleteHabitById(habit.id)
        }
    }

    /** 今日打卡：记录日期 + 计算连续天数 + 触发鼓励 */
    fun checkIn(context: Context, habit: HabitEntity) {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            if (habit.checkInDates.contains(today)) return@launch
            val updated = habit.copy(checkInDates = (habit.checkInDates + today).sorted())
            repo.updateHabit(updated)
            val streak = HabitEncouragement.streakOf(updated.checkInDates)
            _lastCheckIn.value = updated to HabitEncouragement.forCheckIn(streak)
        }
    }

    fun dismissCheckIn() {
        _lastCheckIn.value = null
    }

    // ================= 倒数日操作 =================

    /** 创建倒数日（targetDate 为目标日当天 0 点） */
    fun addCountdown(title: String, targetDate: Long) {
        viewModelScope.launch {
            repo.insertCountdown(CountdownEntity(title = title, targetDate = targetDate))
        }
    }

    /** 编辑倒数日（改名/改日期，重建实体） */
    fun editCountdown(countdown: CountdownEntity, title: String, targetDate: Long) {
        viewModelScope.launch {
            repo.insertCountdown(countdown.copy(title = title, targetDate = targetDate))
        }
    }

    /** 删除倒数日 */
    fun deleteCountdown(countdown: CountdownEntity) {
        viewModelScope.launch {
            repo.deleteCountdownById(countdown.id)
        }
    }

    fun addTodo(
        context: Context,
        title: String,
        description: String?,
        dueTime: Long?,
        remindAt: Long?,
        priority: Int,
        isLongTerm: Boolean = false
    ) {
        viewModelScope.launch {
            val id = repo.insertTodo(
                TodoEntity(
                    title = title,
                    description = description,
                    dueTime = dueTime,
                    remindAt = remindAt,
                    priority = priority,
                    isLongTerm = isLongTerm
                )
            )
            // 长期待办不做到期/提醒调度（无固定日期），今日待办才调度
            if (!isLongTerm && remindAt != null && remindAt > System.currentTimeMillis()) {
                val saved = repo.getTodo(id)
                if (saved != null) {
                    ReminderScheduler.scheduleTodoReminder(context, saved)
                    repo.updateTodo(saved.copy(remindScheduled = true))
                }
            }
        }
    }

    fun toggle(context: Context, todo: TodoEntity) {
        viewModelScope.launch {
            if (!todo.completed) {
                ReminderScheduler.cancelTodoReminder(context, todo.id)
                repo.updateTodo(todo.copy(completed = true, remindScheduled = false))
            } else {
                repo.updateTodo(todo.copy(completed = false))
            }
        }
    }

    /** 编辑待办：更新内容，并重新调度/取消提醒 */
    fun updateTodo(context: Context, todo: TodoEntity) {
        viewModelScope.launch {
            ReminderScheduler.cancelTodoReminder(context, todo.id)
            repo.updateTodo(todo.copy(remindScheduled = false))
            // 今日待办且设置了未来提醒才重新调度（长期待办不调度）
            if (!todo.isLongTerm && todo.remindAt != null && todo.remindAt > System.currentTimeMillis()) {
                ReminderScheduler.scheduleTodoReminder(context, todo)
                repo.updateTodo(todo.copy(remindScheduled = true))
            }
        }
    }

    fun delete(context: Context, todo: TodoEntity) {
        viewModelScope.launch {
            ReminderScheduler.cancelTodoReminder(context, todo.id)
            repo.deleteTodo(todo)
        }
    }

    // ==================== Magic AI 一句话建待办（功能 A） ====================

    /** AI 解析出的待办结构 */
    data class ParsedTodo(
        val title: String,
        val description: String? = null,
        val dueTime: Long? = null,
        val remindAt: Long? = null,
        val priority: Int = 1
    )

    data class AiParseState(
        val parsing: Boolean = false,
        val result: ParsedTodo? = null,
        val error: String? = null,
        val fallbackTitle: String? = null
    )

    private val _aiParse = MutableStateFlow(AiParseState())
    val aiParse: StateFlow<AiParseState> = _aiParse.asStateFlow()

    private val aiClient = AiClient()
    private val aiJson = Json { ignoreUnknownKeys = true }

    /** 调用 Magic AI 把自然语言解析成待办；解析失败自动降级为纯文本标题 */
    fun aiParseTodo(rawText: String) {
        if (rawText.isBlank() || _aiParse.value.parsing) return
        viewModelScope.launch {
            _aiParse.value = AiParseState(parsing = true)
            try {
                val config = repo.aiConfig.collectFirst()
                if (config.apiKey.isBlank()) {
                    _aiParse.value = AiParseState(error = "还没配置 API Key，请先到「设置」页填写")
                    return@launch
                }
                val prompt = AiPrompter.buildTodoParsePrompt(rawText, LocalDateTime.now())
                val reply = withTimeoutOrNull(60_000) {
                    aiClient.chat(
                        baseUrl = config.baseUrl,
                        apiKey = config.apiKey,
                        model = config.model,
                        messages = listOf(AiClient.ChatMessage("user", prompt)),
                        jsonMode = true
                    )
                }
                if (reply == null) {
                    // 超时兜底：降级成纯文本待办，不让用户卡住
                    _aiParse.value = AiParseState(error = "Magic AI 解析超时，已降级为纯文本待办", result = ParsedTodo(title = rawText.trim()))
                    return@launch
                }
                val parsed = parseTodoJson(reply)
                if (parsed != null) {
                    _aiParse.value = AiParseState(result = parsed)
                } else {
                    // AI 返回了但解析失败：降级成纯文本待办，不让用户卡住
                    _aiParse.value = AiParseState(result = ParsedTodo(title = rawText.trim()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _aiParse.value = AiParseState(
                    error = "Magic AI 解析失败：${e.message ?: "未知错误"}",
                    fallbackTitle = rawText.trim()
                )
            }
        }
    }

    /** 确认创建 AI 解析出的待办（isLongTerm 由用户在对话框中切换，默认今日） */
    fun confirmAiTodo(context: Context, isLongTerm: Boolean = false) {
        val parsed = _aiParse.value.result ?: return
        addTodo(context, parsed.title, parsed.description, parsed.dueTime, parsed.remindAt, parsed.priority, isLongTerm)
        _aiParse.value = AiParseState()
    }

    fun dismissAiParse() {
        _aiParse.value = AiParseState()
    }

    private fun parseTodoJson(raw: String): ParsedTodo? {
        // 容错：去掉 ```json 围栏 / 前后噪声，只保留第一个 { ... }
        val jsonStr = raw
            .substringAfter("{")
            .substringBeforeLast("}")
            .let { "{$it}" }
        return try {
            val obj = aiJson.parseToJsonElement(jsonStr).jsonObject
            val title = obj.str("title") ?: return null
            val due = obj.str("dueTime")?.let { parseLocalDateTime(it) }
            val remind = obj.str("remindAt")?.let { parseLocalDateTime(it) }
            val now = System.currentTimeMillis()
            ParsedTodo(
                title = title,
                description = obj.str("description")?.takeIf { it.isNotBlank() },
                priority = obj.str("priority")?.toIntOrNull()?.coerceIn(0, 2) ?: 1,
                dueTime = due,
                // 未指定提醒时，默认截止前 10 分钟
                remindAt = remind ?: due?.minus(10 * 60 * 1000)?.takeIf { it > now }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { if (it is JsonPrimitive) it.content else null }

    private fun parseLocalDateTime(s: String): Long? = try {
        LocalDateTime.parse(s.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
