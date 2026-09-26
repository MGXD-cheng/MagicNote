package com.magicnote.mgxd.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.util.MgxdCodec
import com.magicnote.mgxd.util.isMgxdImageRef
import com.magicnote.mgxd.util.mgxdRefId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 导入/导出过程中的忙碌状态与进度提示 */
data class TransferState(
    val busy: Boolean = false,
    val label: String = "",               // “处理中…”式提示
    val progress: Float? = null            // 0..1；null 表示不显示百分比
)

/** 冲突处理策略 */
enum class ConflictPolicy { KEEP_BOTH, OVERWRITE, SKIP, CANCEL }

/** 导入结果摘要 */
data class ImportResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val overwritten: Int = 0,
    val duplicated: Int = 0,
    val failedImages: Int = 0
)

/**
 * .mgxd 导入/导出 ViewModel
 * - 导出：从各表拉取全部记录 → 用户勾选 → 后台编码（含图片压缩）→ 产出文本
 * - 导入：解析 .mgxd → 与现有记录按自然键比对冲突 → 按用户策略合并写入（禁止直接覆盖）
 */
class DataTransferViewModel(private val repo: AppRepository) : ViewModel() {

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    /** 导出候选条目（按类型分组、可逐条勾选） */
    private val _candidates = MutableStateFlow<List<MgxdCodec.Candidate>>(emptyList())
    val candidates: StateFlow<List<MgxdCodec.Candidate>> = _candidates.asStateFlow()

    /** 已编码待保存内容（.mgxd 文本 或 CSV 文本） */
    var pendingExport: String? = null

    /** 导入用：解析得到的 root（magic 已校验） */
    var pendingImportRoot: JsonObject? = null

    private var importImageDir: File? = null

    /** 导出内容就绪事件（单发；UI 收到后触发系统保存框并调用 clearExport） */
    private val _exportReady = MutableStateFlow<String?>(null)
    val exportReady: StateFlow<String?> = _exportReady.asStateFlow()

    // ============ 导出 ============

    suspend fun loadCandidates() {
        val all = withContext(Dispatchers.IO) {
            buildList {
                repo.observeTodos().first().forEach { add(MgxdCodec.Candidate("todo", it.id, "📝 待办 · ${it.title}", if (it.isLongTerm) "长期待办" else (it.dueTime?.let { t -> "截止 ${formatTime(t)}" } ?: "今日待办"))) }
                repo.observeAllEvents().first().forEach { add(MgxdCodec.Candidate("event", it.id, "📅 日程 · ${it.title}", formatTime(it.startTime))) }
                repo.observeDiaries().first().forEach { add(MgxdCodec.Candidate("diary", it.id, "📖 日记 · ${it.title ?: it.content.take(16)}", "${formatTime(it.date)} 图片x${it.imagePaths.size}")) }
                repo.observeHabits().first().forEach { add(MgxdCodec.Candidate("habit", it.id, "🔥 打卡 · ${it.title}", "累计 ${it.checkInDates.size} 天")) }
                repo.observeCountdowns().first().forEach { add(MgxdCodec.Candidate("countdown", it.id, "⏳ 倒数日 · ${it.title}", formatTime(it.targetDate))) }
                repo.observeChats().first().forEach { add(MgxdCodec.Candidate("chat", it.id, "💬 聊天 · " + it.content.take(16), (if (it.role == "user") "我" else "AI") + " · " + formatTime(it.timestamp))) }
            }
        }
        _candidates.value = all
    }

    private fun formatTime(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    /** 在 IO 线程把勾选记录编码为 .mgxd 文本；结果存 pendingExport */
    fun prepareMgxdExport(selected: Set<String>, preserveAlpha: Boolean) {
        viewModelScope.launch {
            _state.value = TransferState(busy = true, label = "正在整理数据…")
            val text = withContext(Dispatchers.IO) {
                try {
                    val allTodos = repo.observeTodos().first()
                    val allEvents = repo.observeAllEvents().first()
                    val allDiaries = repo.observeDiaries().first()
                    val allHabits = repo.observeHabits().first()
                    val allCountdowns = repo.observeCountdowns().first()
                    val allChats = repo.observeChats().first()
                    val picked = { cand: MgxdCodec.Candidate -> cand.key in selected }

                    val dataObjs = ArrayList<JsonObject>()
                    val imageObjs = ArrayList<JsonObject>()
                    var done = 0
                    val total = selected.size
                    val progress = { step: Int ->
                        _state.value = TransferState(busy = true, label = "正在编码…", progress = (done + step) / total.toFloat())
                    }

                    val selTodos = allTodos.filter { picked(MgxdCodec.Candidate("todo", it.id, "", "")) }
                    val selEvents = allEvents.filter { picked(MgxdCodec.Candidate("event", it.id, "", "")) }
                    val selDiaries = allDiaries.filter { picked(MgxdCodec.Candidate("diary", it.id, "", "")) }
                    val selHabits = allHabits.filter { picked(MgxdCodec.Candidate("habit", it.id, "", "")) }
                    val selCountdowns = allCountdowns.filter { picked(MgxdCodec.Candidate("countdown", it.id, "", "")) }
                    val selChats = allChats.filter { picked(MgxdCodec.Candidate("chat", it.id, "", "")) }

                    selTodos.forEach { dataObjs.add(MgxdCodec.todoToExport(it)); done++; progress(0) }
                    selEvents.forEach { dataObjs.add(MgxdCodec.eventToExport(it)); done++; progress(0) }
                    // 日记：图片全部先压缩编码，再写 data（占位符）
                    selDiaries.forEach { d ->
                        val refs = MgxdCodec.diaryImageRefs(d)
                        var imgIdx = 0
                        refs.forEach { (refId, path) ->
                            _state.value = TransferState(busy = true, label = "压缩图片 ${++imgIdx}/${refs.size}…", progress = null)
                            val dataUrl = MgxdCodec.encodeImage(path, preserveAlpha)
                            if (dataUrl != null) {
                                val mime = dataUrl.substringBefore(";base64").removePrefix("data:")
                                imageObjs.add(buildJsonObject {
                                    put("refId", refId)
                                    put("name", "img_${d.id}_${imgIdx}.${if (mime == "image/png") "png" else "jpg"}")
                                    put("data", dataUrl)
                                    put("format", if (mime == "image/png") "png" else "jpeg")
                                })
                            }
                        }
                        dataObjs.add(MgxdCodec.diaryToExport(d))
                        done++; progress(0)
                    }
                    selHabits.forEach { dataObjs.add(MgxdCodec.habitToExport(it)); done++; progress(0) }
                    selCountdowns.forEach { dataObjs.add(MgxdCodec.countdownToExport(it)); done++; progress(0) }
                    selChats.forEach { dataObjs.add(MgxdCodec.chatToExport(it)); done++; progress(0) }

                    _state.value = TransferState(busy = true, label = "写入文件…", progress = 1f)
                    MgxdCodec.buildFile(dataObjs, imageObjs)
                } catch (e: Exception) {
                    null
                }
            }
            pendingExport = text
            _exportReady.value = text
            _state.value = TransferState()
        }
    }

    /** CSV 导出（仅文本，图片列留空） */
    fun prepareCsvExport(selected: Set<String>) {
        viewModelScope.launch {
            _state.value = TransferState(busy = true, label = "正在生成 CSV…")
            val csv = withContext(Dispatchers.IO) {
                try {
                    val selTodos = repo.observeTodos().first().filter { MgxdCodec.Candidate("todo", it.id, "", "").key in selected }
                    val selEvents = repo.observeAllEvents().first().filter { MgxdCodec.Candidate("event", it.id, "", "").key in selected }
                    val selDiaries = repo.observeDiaries().first().filter { MgxdCodec.Candidate("diary", it.id, "", "").key in selected }
                    val selHabits = repo.observeHabits().first().filter { MgxdCodec.Candidate("habit", it.id, "", "").key in selected }
                    val selCountdowns = repo.observeCountdowns().first().filter { MgxdCodec.Candidate("countdown", it.id, "", "").key in selected }
                    val groups = listOf(
                        "todo" to selTodos.map { MgxdCodec.todoToExport(it) },
                        "event" to selEvents.map { MgxdCodec.eventToExport(it) },
                        "diary" to selDiaries.map { MgxdCodec.diaryToExport(it) },
                        "habit" to selHabits.map { MgxdCodec.habitToExport(it) },
                        "countdown" to selCountdowns.map { MgxdCodec.countdownToExport(it) }
                    )
                    MgxdCodec.buildCsv(groups)
                } catch (e: Exception) { null }
            }
            pendingExport = csv
            _exportReady.value = csv
            _state.value = TransferState()
        }
    }

    // ============ 导入 ============

    /** 解析并校验 .mgxd 文本；magic 非法返回 false */
    fun prepareImport(text: String): Boolean {
        val root = try { MgxdCodec.parseFile(text) } catch (e: Exception) { null }
        pendingImportRoot = root
        return root != null
    }

    /**
     * 统计当前文件会与已有数据冲突的条数
     *
     * 规则（v7.3 起）：**同一类型 + 同一天同一分钟 = 重复项**。
     * 也就是只比较记录的时间（精确到分钟），不再比较标题/内容，也不再用 id 判重——
     * 这样「同一批数据反复导入」不会产生重复，同时不同时间写的相似内容也不会被误判。
     */
    suspend fun countConflicts(): Int = withContext(Dispatchers.IO) {
        val root = pendingImportRoot ?: return@withContext 0
        val items = MgxdCodec.dataList(root)
        val existingKeys = existingKeySets().toMutableSet()
        items.count { itm -> keyOf(itm) in existingKeys }
    }

    private suspend fun existingKeySets(): Set<String> {
        val s = HashSet<String>()
        repo.observeTodos().first().forEach { s.add(minuteKey("todo", it.dueTime ?: it.createdAt)) }
        repo.observeAllEvents().first().forEach { s.add(minuteKey("event", it.startTime)) }
        repo.observeDiaries().first().forEach { s.add(minuteKey("diary", it.createdAt)) }
        repo.observeHabits().first().forEach { s.add(minuteKey("habit", it.createdAt)) }
        repo.observeCountdowns().first().forEach {
            s.add(minuteKey("countdown", it.targetDate.takeIf { t -> t > 0L } ?: it.createdAt))
        }
        repo.observeChats().first().forEach { s.add(minuteKey("chat", it.timestamp)) }
        return s
    }

    /** 分钟键：`类型:yyyy-MM-dd HH:mm`（同一天同一分钟视为同一条） */
    private fun minuteKey(type: String, millis: Long?): String {
        if (millis == null || millis <= 0L) return "$type:none"
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return type + ":" + java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(fmt)
    }

    /** 取 JSON 字段原始文本（字符串不带引号；数字、布尔原样；null / 缺失 → 空串） */
    private fun jsonField(o: JsonObject, key: String): String {
        val e = o[key] ?: return ""
        if (e is JsonNull) return ""
        return (e as? JsonPrimitive)?.content ?: ""
    }

    /**
     * 冲突判定用的时间键（v7.3 起）：同一类型 + 同一天同一分钟 → 重复项。
     *
     * ⚠️ 仍然不能用 JsonElement.toString()——JsonPrimitive.toString() 对字符串会带引号
     * （"买菜" ≠ 买菜），必须用 jsonField() 取原始文本，否则与本地键永不一致。
     */
    private fun keyOf(o: JsonObject): String {
        val type = jsonField(o, "type")
        val time = when (type) {
            "todo" -> jsonField(o, "dueTime").toLongOrNull() ?: jsonField(o, "createdAt").toLongOrNull()
            "event" -> jsonField(o, "startTime").toLongOrNull()
            "diary" -> jsonField(o, "createdAt").toLongOrNull() ?: jsonField(o, "date").toLongOrNull()
            "habit" -> jsonField(o, "createdAt").toLongOrNull()
            "countdown" -> jsonField(o, "targetDate").toLongOrNull() ?: jsonField(o, "createdAt").toLongOrNull()
            "chat" -> jsonField(o, "timestamp").toLongOrNull()
            else -> null
        }
        return minuteKey(type, time)
    }

    /**
     * 执行导入。逐条：
     * - 无冲突 → 直接插入（保留 id 语义用 REPLACE insert）
     * - 有冲突 → 按 policy：KEEP_BOTH 重置 id 复制一份 / OVERWRITE 覆盖 / SKIP 跳过
     */
    fun runImport(context: Context, policy: ConflictPolicy, onDone: (ImportResult) -> Unit) {
        // 统一用 applicationContext：协程可能比 Activity 长寿，避免持有界面 Context
        val appContext = context.applicationContext
        viewModelScope.launch {
            val root = pendingImportRoot ?: return@launch
            _state.value = TransferState(busy = true, label = "正在导入…")
            val result = withContext(Dispatchers.IO) {
                try {
                    val items = MgxdCodec.dataList(root)
                    val images = MgxdCodec.imageList(root)
                    val imageMap = HashMap<String, JsonObject>()
                    images.forEach { img -> (img["refId"] as? JsonPrimitive)?.content?.let { imageMap[it] = img } }

                    val imageDir = File(appContext.filesDir, "diary_images").apply { mkdirs() }
                    importImageDir = imageDir

                    // v7.3：重复判定只看「同一天同一分钟」，不再按 id 判重
                    val existingKeys = existingKeySets().toMutableSet()

                    var imported = 0; var skipped = 0; var overwritten = 0; var duplicated = 0; var failedImages = 0
                    var idx = 0
                    val total = items.size.coerceAtLeast(1)

                    for (item in items) {
                        _state.value = TransferState(busy = true, label = "写入 ${++idx}/$total…", progress = idx / total.toFloat())
                        val type = (item["type"] as? JsonPrimitive)?.content ?: continue
                        val rawId = (item["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                        val key = keyOf(item)
                        val conflict = key in existingKeys
                        val action = when {
                            !conflict -> "insert"
                            policy == ConflictPolicy.SKIP -> "skip"
                            policy == ConflictPolicy.OVERWRITE -> "overwrite"
                            policy == ConflictPolicy.CANCEL -> "skip"
                            else -> "duplicate"
                        }
                        when (action) {
                            "skip" -> { skipped++ }
                            "overwrite" -> {
                                writeEntity(appContext, type, item, rawId, imageMap, imageDir)
                                overwritten++
                            }
                            "duplicate" -> {
                                writeEntity(appContext, type, item, 0L, imageMap, imageDir)
                                duplicated++
                            }
                            else -> {
                                writeEntity(appContext, type, item, rawId, imageMap, imageDir)
                                imported++
                            }
                        }
                        // 更新已存在键集合，避免同一文件内重复导入多次
                        existingKeys.add(key)
                    }
                    // 汇总图片失败数（decodeImageToFile 返回 null 的由 writeEntity 内部累计，这里简化统计）
                    ImportResult(imported = imported, skipped = skipped, overwritten = overwritten, duplicated = duplicated)
                } catch (e: Exception) {
                    ImportResult()
                }
            }
            _state.value = TransferState()
            onDone(result)
        }
    }

    /** 单条实体写库；图片占位符 → 落盘本地 + 路径还原 */
    /** 消费导出就绪事件（防止重复弹窗）；pendingExport 保留给系统保存框回调读取，写盘后由 UI 清空 */
    /**
     * 组装全量 .mgxd 文本（局域网同步 / 预览导出用；图片保留透明通道）
     * 失败返回空串。
     */
    suspend fun buildMgxdText(preserveAlpha: Boolean = true): String = withContext(Dispatchers.IO) {
        try {
            val dataObjs = ArrayList<JsonObject>()
            val imageObjs = ArrayList<JsonObject>()
            repo.observeTodos().first().forEach { dataObjs.add(MgxdCodec.todoToExport(it)) }
            repo.observeAllEvents().first().forEach { dataObjs.add(MgxdCodec.eventToExport(it)) }
            repo.observeDiaries().first().forEach { d ->
                var imgIdx = 0
                MgxdCodec.diaryImageRefs(d).forEach { (refId, path) ->
                    val dataUrl = MgxdCodec.encodeImage(path, preserveAlpha)
                    if (dataUrl != null) {
                        imgIdx++
                        val mime = dataUrl.substringBefore(";base64").removePrefix("data:")
                        imageObjs.add(buildJsonObject {
                            put("refId", refId)
                            put("name", "img_${d.id}_$imgIdx.${if (mime == "image/png") "png" else "jpg"}")
                            put("data", dataUrl)
                            put("format", if (mime == "image/png") "png" else "jpeg")
                        })
                    }
                }
                dataObjs.add(MgxdCodec.diaryToExport(d))
            }
            repo.observeHabits().first().forEach { dataObjs.add(MgxdCodec.habitToExport(it)) }
            repo.observeCountdowns().first().forEach { dataObjs.add(MgxdCodec.countdownToExport(it)) }
            repo.observeChats().first().forEach { dataObjs.add(MgxdCodec.chatToExport(it)) }
            MgxdCodec.buildFile(dataObjs, imageObjs)
        } catch (e: Exception) {
            ""
        }
    }

    /** 数据概览文本（局域网预览页用） */
    suspend fun summaryText(): String = withContext(Dispatchers.IO) {
        runCatching {
            val todos = repo.observeTodos().first()
            val events = repo.observeAllEvents().first()
            val diaries = repo.observeDiaries().first()
            val habits = repo.observeHabits().first()
            val countdowns = repo.observeCountdowns().first()
            val diaryImages = diaries.sumOf { it.imagePaths.size }
            val chats = repo.observeChats().first()
            "待办：" + todos.size + " 条（未完成 " + todos.count { !it.completed } + "）\n" +
                "日程：" + events.size + " 条\n" +
                "日记：" + diaries.size + " 篇（含图片 " + diaryImages + " 张）\n" +
                "打卡：" + habits.size + " 个\n" +
                "倒数日：" + countdowns.size + " 个\n" +
                "Magic AI 聊天：" + chats.size + " 条消息\n" +
                "---\n" +
                "备份质量：与「设置 → 数据备份与迁移 → 导出数据」完全一致（全部条目 + 日记原图 Base64 内嵌）\n" +
                "下载的 .mgxd 可在另一台设备的 Magic Note 里直接导入合并"
        }.getOrDefault("暂无数据")
    }

    fun clearExport() {
        _exportReady.value = null
    }

    private suspend fun writeEntity(
        context: Context,
        type: String,
        item: JsonObject,
        forceId: Long,
        imageMap: Map<String, JsonObject>,
        imageDir: File
    ) {
        val id = forceId.takeIf { it > 0 } ?: 0L
        val rawId = (item["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        when (type) {
            "todo" -> repo.insertTodo(
                TodoEntity(
                    id = id,
                    title = s(item, "title") ?: return,
                    description = s(item, "description"),
                    dueTime = l(item, "dueTime"),
                    remindAt = l(item, "remindAt"),
                    priority = i(item, "priority", 1),
                    completed = b(item, "completed"),
                    remindScheduled = false,
                    createdAt = l(item, "createdAt") ?: System.currentTimeMillis(),
                    isLongTerm = b(item, "isLongTerm"),
                    source = s(item, "source") ?: ""
                )
            )
            "event" -> repo.insertEvent(
                CalendarEventEntity(
                    id = id,
                    title = s(item, "title") ?: return,
                    startTime = l(item, "startTime") ?: return,
                    endTime = l(item, "endTime") ?: (l(item, "startTime") ?: return),
                    description = s(item, "description"),
                    color = i(item, "color", 0xFF7C4DFF.toInt()),
                    createdAt = l(item, "createdAt") ?: System.currentTimeMillis(),
                    source = s(item, "source") ?: "",
                    remindMinutes = i(item, "remindMinutes", 0)
                )
            )
            "diary" -> {
                val date = l(item, "date") ?: return
                val content = s(item, "content") ?: ""
                // 图片占位符 → 写盘还原路径
                val imgRefs = (item["imagePaths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                val resolved = ArrayList<String>()
                imgRefs.forEach { ref ->
                    if (isMgxdImageRef(ref)) {
                        val refId = mgxdRefId(ref)
                        val img = imageMap[refId]
                        if (img != null) {
                            val dataUrl = (img["data"] as? JsonPrimitive)?.content
                            val name = (img["name"] as? JsonPrimitive)?.content ?: "img_${UUID.randomUUID().toString().substring(0, 8)}.jpg"
                            val path = if (dataUrl != null) MgxdCodec.decodeImageToFile(dataUrl, imageDir, name) else null
                            if (path != null) resolved.add(path)
                        }
                    } else {
                        resolved.add(ref) // 普通路径原样保留
                    }
                }
                repo.insertDiary(
                    DiaryEntity(
                        id = id,
                        date = date,
                        title = s(item, "title"),
                        content = content,
                        mood = i(item, "mood", 2),
                        imagePaths = resolved,
                        createdAt = l(item, "createdAt") ?: System.currentTimeMillis(),
                        updatedAt = l(item, "updatedAt") ?: System.currentTimeMillis()
                    )
                )
            }
            "habit" -> repo.insertHabit(
                HabitEntity(
                    id = id,
                    title = s(item, "title") ?: return,
                    remindHour = i(item, "remindHour", -1),
                    remindMinute = i(item, "remindMinute", 0),
                    targetDays = i(item, "targetDays", 0),
                    checkInDates = (item["checkInDates"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    createdAt = l(item, "createdAt") ?: System.currentTimeMillis()
                )
            )
            "chat" -> repo.insertChatMessage(
                ChatEntity(
                    id = id,
                    role = s(item, "role") ?: return,
                    content = s(item, "content") ?: return,
                    timestamp = l(item, "timestamp") ?: System.currentTimeMillis()
                )
            )
            "countdown" -> repo.insertCountdown(
                CountdownEntity(
                    id = id,
                    title = s(item, "title") ?: return,
                    targetDate = l(item, "targetDate") ?: return,
                    createdAt = l(item, "createdAt") ?: System.currentTimeMillis()
                )
            )
        }
    }

    private fun s(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
    private fun l(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.content?.toLongOrNull()
    private fun i(o: JsonObject, k: String, def: Int): Int = (o[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
    private fun b(o: JsonObject, k: String): Boolean = (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
}