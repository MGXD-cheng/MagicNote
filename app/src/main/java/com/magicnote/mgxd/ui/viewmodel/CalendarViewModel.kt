package com.magicnote.mgxd.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.notify.ReminderScheduler
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

class CalendarViewModel(
    private val repo: AppRepository,
    private val appContext: Context
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _events = MutableStateFlow<List<CalendarEventEntity>>(emptyList())
    val events: StateFlow<List<CalendarEventEntity>> = _events.asStateFlow()

    /** 最近一次时间冲突自动调整的提示（一次性，UI 消费后清空） */
    private val _lastAdjustHint = MutableStateFlow<String?>(null)
    val lastAdjustHint: StateFlow<String?> = _lastAdjustHint.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAllEvents().collect { _events.value = it }
        }
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun eventsOn(date: LocalDate): List<CalendarEventEntity> {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = start + 24 * 60 * 60 * 1000
        return _events.value.filter { it.startTime in start until end }
    }

    fun addEvent(title: String, start: Long, end: Long, description: String?, color: Int, remindMinutes: Int = 0) {
        viewModelScope.launch {
            // 时间冲突自动对齐：后一个日程顺延到已有日程结束之后
            val (s, e) = EventConflictResolver.resolve(start, end, _events.value)
            val id = repo.insertEvent(
                CalendarEventEntity(
                    title = title, startTime = s, endTime = e,
                    description = description, color = color, remindMinutes = remindMinutes
                )
            )
            if (s != start) {
                _lastAdjustHint.value = "「$title」与已有日程时间冲突，已自动顺延到 ${TimeUtils.formatMillis(s, "HH:mm")}"
            }
            if (remindMinutes > 0) {
                repo.getEvent(id)?.let { ReminderScheduler.scheduleEventReminder(appContext, it) }
            }
        }
    }

    /** 编辑日程：更新内容，同样自动处理时间冲突（排除自身），并重排提醒 */
    fun updateEvent(event: CalendarEventEntity) {
        viewModelScope.launch {
            val (s, e) = EventConflictResolver.resolve(event.startTime, event.endTime, _events.value, event.id)
            repo.updateEvent(event.copy(startTime = s, endTime = e))
            if (s != event.startTime) {
                _lastAdjustHint.value = "「${event.title}」与已有日程时间冲突，已自动顺延到 ${TimeUtils.formatMillis(s, "HH:mm")}"
            }
            // 先取消旧提醒，再按新时间/新提醒设置调度
            ReminderScheduler.cancelEventReminder(appContext, event.id)
            ReminderScheduler.scheduleEventReminder(appContext, event.copy(startTime = s, endTime = e))
        }
    }

    /** 消费时间调整提示 */
    fun consumeAdjustHint() {
        _lastAdjustHint.value = null
    }

    fun deleteEvent(event: CalendarEventEntity) {
        viewModelScope.launch {
            ReminderScheduler.cancelEventReminder(appContext, event.id)
            repo.deleteEvent(event)
        }
    }

    // ==================== AI 规划计划 ====================

    /** AI 规划的一个步骤 */
    data class PlanStep(
        val type: String,          // "event" / "todo"
        val title: String,
        val startTime: Long? = null,
        val endTime: Long? = null,
        val remindMinutes: Int = 0,
        val dueTime: Long? = null,
        val priority: Int = 1
    )

    /** AI 规划完整方案 */
    data class PlanResult(
        val planTitle: String,
        val summary: String,
        val steps: List<PlanStep>
    )

    /** 规划流程状态 */
    sealed interface PlanState {
        data object Idle : PlanState
        data object Loading : PlanState
        data class Success(val plan: PlanResult) : PlanState
        data class Error(val message: String) : PlanState
    }

    private data class PlanInput(
        val requirement: String,
        val resources: String,
        val deadline: Long,
        val priority: Int
    )

    private val _planState = MutableStateFlow<PlanState>(PlanState.Idle)
    val planState: StateFlow<PlanState> = _planState.asStateFlow()

    /** 采纳结果提示（一次性） */
    private val _planApplyResult = MutableStateFlow<String?>(null)
    val planApplyResult: StateFlow<String?> = _planApplyResult.asStateFlow()

    private var lastPlanInput: PlanInput? = null
    private val planJson = Json { ignoreUnknownKeys = true }

    companion object {
        /** AI 规划请求超时：prompt 较长，模型思考+生成需要更多时间 */
        private const val PLAN_TIMEOUT_SECONDS = 120L
        private const val PLAN_TIMEOUT_MS = PLAN_TIMEOUT_SECONDS * 1000L
    }

    /** 生成计划方案（AI 分析需求/资源/截止/优先级） */
    fun generatePlan(requirement: String, resources: String, deadline: Long, priority: Int) {
        if (requirement.isBlank() || _planState.value == PlanState.Loading) return
        lastPlanInput = PlanInput(requirement.trim(), resources.trim(), deadline, priority)
        viewModelScope.launch {
            _planState.value = PlanState.Loading
            try {
                val config = repo.aiConfig.first()
                if (config.apiKey.isBlank()) {
                    _planState.value = PlanState.Error("还未配置 API Key，请先到「设置」页填写（支持 OpenAI 及各类兼容接口）")
                    return@launch
                }
                val todos = repo.observeTodayTodos().first()
                val events = _events.value
                val prompt = AiPrompter.buildPlanPrompt(
                    requirement = requirement.trim(),
                    resources = resources.trim(),
                    deadline = deadline,
                    priorityLabel = when (priority) { 0 -> "低"; 2 -> "高"; else -> "中" },
                    todos = todos,
                    events = events
                )
                val reply = withContext(Dispatchers.IO) {
                    // 规划 prompt 较长：放宽到 120s（AiClient per-call 覆盖），并显式超时兜底
                    // jsonMode=true 让模型按 JSON 输出；不支持的端点由 AiClient 自动降级重试
                    withTimeoutOrNull(PLAN_TIMEOUT_MS) {
                        AiClient().chat(
                            baseUrl = config.baseUrl,
                            apiKey = config.apiKey,
                            model = config.model,
                            messages = listOf(AiClient.ChatMessage("user", prompt)),
                            jsonMode = true,
                            timeoutSeconds = PLAN_TIMEOUT_SECONDS
                        )
                    }
                }
                if (reply == null) {
                    _planState.value = PlanState.Error("AI 生成超时（${PLAN_TIMEOUT_SECONDS}s），请稍后重试或缩短需求描述")
                    return@launch
                }
                val plan = parsePlanJson(reply)
                _planState.value = if (plan != null) {
                    PlanState.Success(plan)
                } else {
                    PlanState.Error("AI 返回的方案无法解析，请重新生成试试")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _planState.value = PlanState.Error("生成失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 重新生成（沿用上次输入） */
    fun regeneratePlan() {
        val input = lastPlanInput ?: return
        generatePlan(input.requirement, input.resources, input.deadline, input.priority)
    }

    /** 采纳方案：批量登记为日程（带提醒）与待办 */
    fun applyPlan() {
        val plan = (_planState.value as? PlanState.Success)?.plan ?: return
        viewModelScope.launch {
            var eventCount = 0
            var todoCount = 0
            // 本地累积已插入的日程，保证同一批计划内步骤间的时间冲突也能被检测到
            val localEvents = _events.value.toMutableList()
            plan.steps.forEach { step ->
                try {
                    val isEvent = step.type.contains("event") || step.startTime != null
                    if (isEvent) {
                        val start = step.startTime ?: step.dueTime ?: System.currentTimeMillis()
                        val end = step.endTime ?: (start + 60 * 60 * 1000)
                        val (s, e) = EventConflictResolver.resolve(start, end, localEvents)
                        val id = repo.insertEvent(
                            CalendarEventEntity(
                                title = step.title, startTime = s, endTime = e,
                                remindMinutes = step.remindMinutes.coerceIn(0, 60 * 24 * 7),
                                source = "magic_ai"
                            )
                        )
                        localEvents += CalendarEventEntity(
                            id = id, title = step.title, startTime = s, endTime = e,
                            remindMinutes = step.remindMinutes.coerceIn(0, 60 * 24 * 7),
                            source = "magic_ai"
                        )
                        if (step.remindMinutes > 0) {
                            repo.getEvent(id)?.let { ReminderScheduler.scheduleEventReminder(appContext, it) }
                        }
                        eventCount++
                    } else {
                        val due = step.dueTime
                        val remindAt = due?.minus(10 * 60 * 1000L)
                        val id = repo.insertTodo(
                            TodoEntity(
                                title = step.title,
                                dueTime = due,
                                remindAt = remindAt,
                                priority = step.priority.coerceIn(0, 2),
                                source = "magic_ai"
                            )
                        )
                        if (remindAt != null && remindAt > System.currentTimeMillis()) {
                            repo.getTodo(id)?.let { saved ->
                                ReminderScheduler.scheduleTodoReminder(appContext, saved)
                                repo.updateTodo(saved.copy(remindScheduled = true))
                            }
                        }
                        todoCount++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单步失败不中断整体
                }
            }
            _planState.value = PlanState.Idle
            _planApplyResult.value = "已采纳「${plan.planTitle}」：登记 $eventCount 个日程、$todoCount 个待办"
        }
    }

    /** 消费采纳结果提示 */
    fun consumePlanApplyResult() {
        _planApplyResult.value = null
    }

    /** 关闭规划对话框时复位 */
    fun resetPlan() {
        _planState.value = PlanState.Idle
    }

    private fun parsePlanJson(raw: String): PlanResult? {
        val jsonStr = raw.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
        return try {
            val obj = planJson.parseToJsonElement(jsonStr).jsonObject
            val title = obj.str("planTitle") ?: obj.str("title") ?: "AI 计划"
            val summary = obj.str("summary") ?: ""
            val steps = (obj["steps"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val stepTitle = o.str("title") ?: return@mapNotNull null
                PlanStep(
                    type = o.str("type") ?: "",
                    title = stepTitle,
                    startTime = o.str("startTime")?.let { parseLocalDateTime(it) },
                    endTime = o.str("endTime")?.let { parseLocalDateTime(it) },
                    remindMinutes = o.str("remindMinutes")?.toIntOrNull() ?: 0,
                    dueTime = o.str("dueTime")?.let { parseLocalDateTime(it) },
                    priority = o.str("priority")?.toIntOrNull() ?: 1
                )
            } ?: emptyList()
            if (steps.isEmpty()) null else PlanResult(title, summary, steps)
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
