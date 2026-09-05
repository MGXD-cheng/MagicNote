package com.magicnote.mgxd.data.repo

import com.magicnote.mgxd.data.db.AppDatabase
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.ChatEntity
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.data.prefs.UserPrefs
import kotlinx.coroutines.flow.Flow

/**
 * 统一仓库：向上层屏蔽数据库与偏好设置的细节
 */
class AppRepository(
    private val db: AppDatabase,
    private val prefs: UserPrefs
) {

    // ================= 待办 =================
fun observeTodos(): Flow<List<TodoEntity>> = db.todoDao().observeAll()
fun observeTodayTodos(): Flow<List<TodoEntity>> = db.todoDao().observeToday()
fun observeLongTermTodos(): Flow<List<TodoEntity>> = db.todoDao().observeLongTerm()
fun observeActiveTodos(): Flow<List<TodoEntity>> = db.todoDao().observeActive()
    suspend fun getTodo(id: Long): TodoEntity? = db.todoDao().getById(id)
    suspend fun insertTodo(todo: TodoEntity): Long = db.todoDao().insert(todo)
    suspend fun updateTodo(todo: TodoEntity) = db.todoDao().update(todo)
    suspend fun deleteTodo(todo: TodoEntity) = db.todoDao().delete(todo)
    suspend fun deleteTodoById(id: Long) = db.todoDao().deleteById(id)
    suspend fun getScheduledReminders(): List<TodoEntity> = db.todoDao().getScheduledReminders()
    suspend fun deleteCompletedTodosBefore(cutoff: Long) = db.todoDao().deleteCompletedBefore(cutoff)

    suspend fun deleteCompletedExpiredTodosBefore(cutoff: Long) = db.todoDao().deleteCompletedExpiredBefore(cutoff)

    suspend fun toggleTodo(todo: TodoEntity) {
        db.todoDao().update(todo.copy(completed = !todo.completed))
    }

    // ================= 日历日程 =================
    fun observeEvents(start: Long, end: Long): Flow<List<CalendarEventEntity>> =
        db.calendarEventDao().observeBetween(start, end)

    fun observeAllEvents(): Flow<List<CalendarEventEntity>> = db.calendarEventDao().observeAll()
    suspend fun getEvent(id: Long): CalendarEventEntity? = db.calendarEventDao().getById(id)
    suspend fun insertEvent(event: CalendarEventEntity): Long = db.calendarEventDao().insert(event)
    suspend fun updateEvent(event: CalendarEventEntity) = db.calendarEventDao().update(event)
    suspend fun deleteEvent(event: CalendarEventEntity) = db.calendarEventDao().delete(event)
    suspend fun deleteEventById(id: Long) = db.calendarEventDao().deleteById(id)

    // ================= 日记 =================
fun observeDiaries(): Flow<List<DiaryEntity>> = db.diaryDao().observeAll()
fun observeDiaryByDay(dayStart: Long): Flow<List<DiaryEntity>> = db.diaryDao().observeByDay(dayStart)
suspend fun getDiaryByDay(dayStart: Long): DiaryEntity? = db.diaryDao().getByDay(dayStart)
suspend fun insertDiary(diary: DiaryEntity): Long = db.diaryDao().insert(diary)
suspend fun updateDiary(diary: DiaryEntity) = db.diaryDao().update(diary)
suspend fun deleteDiary(diary: DiaryEntity) = db.diaryDao().delete(diary)
suspend fun deleteDiaryById(id: Long) = db.diaryDao().deleteById(id)

// ================= Magic AI 聊天历史 =================
fun observeChats(): Flow<List<ChatEntity>> = db.chatDao().observeAll()
suspend fun insertChat(role: String, content: String): Long =
    db.chatDao().insert(ChatEntity(role = role, content = content))
suspend fun clearChats() = db.chatDao().clearAll()

    // ================= 每日打卡 =================
    fun observeHabits(): Flow<List<HabitEntity>> = db.habitDao().observeAll()
    suspend fun getHabit(id: Long): HabitEntity? = db.habitDao().getById(id)
    suspend fun insertHabit(habit: HabitEntity): Long = db.habitDao().insert(habit)
    suspend fun updateHabit(habit: HabitEntity) = db.habitDao().update(habit)
    suspend fun deleteHabitById(id: Long) = db.habitDao().deleteById(id)

    // ================= 倒数日 =================
    fun observeCountdowns(): Flow<List<CountdownEntity>> = db.countdownDao().observeAll()
    suspend fun insertCountdown(countdown: CountdownEntity): Long = db.countdownDao().insert(countdown)
    suspend fun deleteCountdownById(id: Long) = db.countdownDao().deleteById(id)

    // ================= 偏好 =================
    val aiConfig get() = prefs.aiConfig
    val notifyConfig get() = prefs.notifyConfig
    val screenTimeConfig get() = prefs.screenTimeConfig
    val categoryOverrides get() = prefs.categoryOverrides
    val pureMode get() = prefs.pureMode
    val moduleConfig get() = prefs.moduleConfig
    val themeMode get() = prefs.themeMode
    suspend fun saveAiConfig(
        baseUrl: String, apiKey: String, model: String,
        personalityId: String, customPrompt: String
    ) = prefs.saveAiConfig(baseUrl, apiKey, model, personalityId, customPrompt)

    suspend fun saveNotifyConfig(
        dailySummaryEnabled: Boolean, dailySummaryHour: Int,
        dailySummaryMinute: Int, notificationEnabled: Boolean
    ) = prefs.saveNotifyConfig(dailySummaryEnabled, dailySummaryHour, dailySummaryMinute, notificationEnabled)

    suspend fun saveScreenTimeConfig(enabled: Boolean, thresholdMinutes: Int) =
        prefs.saveScreenTimeConfig(enabled, thresholdMinutes)

    suspend fun savePureMode(enabled: Boolean) = prefs.savePureMode(enabled)
    suspend fun saveThemeMode(mode: String) = prefs.saveThemeMode(mode)

    suspend fun saveModuleConfig(todoEnabled: Boolean, calendarEnabled: Boolean, diaryEnabled: Boolean) =
        prefs.saveModuleConfig(todoEnabled, calendarEnabled, diaryEnabled)

    val diaryAutoReply get() = prefs.diaryAutoReply
    suspend fun saveDiaryAutoReply(enabled: Boolean) = prefs.saveDiaryAutoReply(enabled)

    val modelVision get() = prefs.modelVision
    suspend fun saveModelVision(enabled: Boolean) = prefs.saveModelVision(enabled)

    suspend fun saveScreenTimeWarn(pkg: String, at: Long) =
        prefs.saveScreenTimeWarn(pkg, at)

    suspend fun saveCategoryOverride(pkg: String, category: String) =
    prefs.saveCategoryOverride(pkg, category)

fun observeLastAiTodoRemindAt(): Flow<Long> = prefs.lastAiTodoRemindAt
suspend fun saveLastAiTodoRemindAt(at: Long) = prefs.saveAiTodoRemindAt(at)

    suspend fun removeCategoryOverride(pkg: String) =
        prefs.removeCategoryOverride(pkg)
}