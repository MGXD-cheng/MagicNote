package com.magicnote.mgxd.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Magic Note 数据导出/导入编解码（.mgxd 专属格式）
 *
 * 文件结构（纯 JSON + Base64，禁止二进制混合）：
 * {
 *   "magic": "MGXD",
 *   "version": "1.0",
 *   "exportedAt": 毫秒时间戳,
 *   "device": "MagicNote",
 *   "data": [ { "type": "todo|event|diary|habit|countdown", ...业务字段 }, ... ],
 *   "images": [ { "refId": "diary_<原id>_<序号>", "name": "img_xxx.jpg", "data": "data:image/jpeg;base64,....", "format": "jpeg" } ]
 * }
 * diary 的 imagePaths 在导出时替换为 "__MGXD_REF:diary_<原id>_<序号>" 占位符，导入时还原为本地文件路径。
 */
object MgxdCodec {

    const val MAGIC = "MGXD"
    const val VERSION = "1.0"
    const val REF_PREFIX = "__MGXD_REF:"
    private val json = Json { ignoreUnknownKeys = true }

    /** 供导出对话框展示的候选条目（同一类型的多条可逐条勾选；key 全局唯一 = type:id） */
    data class Candidate(
        val type: String,   // todo / event / diary / habit / countdown
        val id: Long,
        val title: String,
        val subtitle: String,
        val key: String = "$type:$id",
        val selected: Boolean = true
    )

    data class ExportItem(
        val type: String,
        val id: Long,
        val obj: JsonObject,     // data 条目对象（不含 type/id 已含）
        val imageRefs: Map<String, String> = emptyMap() // 图片占位符refId -> 本地图片绝对路径
    )

    // ============ 导出：实体 → data 条目 ============

    fun todoToExport(todo: TodoEntity): JsonObject = buildJsonObject {
        put("type", "todo")
        put("id", todo.id)
        put("title", todo.title)
        todo.description?.let { put("description", it) }
        todo.dueTime?.let { put("dueTime", it) }
        todo.remindAt?.let { put("remindAt", it) }
        put("priority", todo.priority)
        put("completed", todo.completed)
        put("remindScheduled", false) // 导入后提醒统一重新调度
        put("createdAt", todo.createdAt)
        put("isLongTerm", todo.isLongTerm)
        if (todo.source.isNotBlank()) put("source", todo.source)
    }

    fun eventToExport(e: CalendarEventEntity): JsonObject = buildJsonObject {
        put("type", "event")
        put("id", e.id)
        put("title", e.title)
        put("startTime", e.startTime)
        put("endTime", e.endTime)
        e.description?.let { put("description", it) }
        put("color", e.color)
        put("createdAt", e.createdAt)
        if (e.source.isNotBlank()) put("source", e.source)
        put("remindMinutes", e.remindMinutes)
    }

    fun diaryToExport(d: DiaryEntity): JsonObject = buildJsonObject {
        put("type", "diary")
        put("id", d.id)
        put("date", d.date)
        d.title?.let { put("title", it) }
        put("content", d.content)
        put("mood", d.mood)
        put("createdAt", d.createdAt)
        put("updatedAt", d.updatedAt)
        val refs = buildJsonArray {
            d.imagePaths.forEachIndexed { idx, path ->
                add(JsonPrimitive(REF_PREFIX + "diary_${d.id}_$idx"))
            }
        }
        put("imagePaths", refs)
    }

    fun habitToExport(h: HabitEntity): JsonObject = buildJsonObject {
        put("type", "habit")
        put("id", h.id)
        put("title", h.title)
        put("remindHour", h.remindHour)
        put("remindMinute", h.remindMinute)
        put("targetDays", h.targetDays)
        put("checkInDates", JsonArray(h.checkInDates.map { JsonPrimitive(it) }))
        put("createdAt", h.createdAt)
    }

    fun countdownToExport(c: CountdownEntity): JsonObject = buildJsonObject {
        put("type", "countdown")
        put("id", c.id)
        put("title", c.title)
        put("targetDate", c.targetDate)
        put("createdAt", c.createdAt)
    }

    // ============ 组装 / 解析 ============

    /** 组装 .mgxd 完整 JSON 文本 */
    fun buildFile(data: List<JsonObject>, images: List<JsonObject>): String {
        val root = buildJsonObject {
            put("magic", MAGIC)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("device", "MagicNote")
            put("data", JsonArray(data))
            put("images", JsonArray(images))
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /** 解析 .mgxd 顶层；magic 不匹配返回 null */
    fun parseFile(text: String): JsonObject? = try {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["magic"]?.let { it is JsonPrimitive && it.content == MAGIC } != true) null else root
    } catch (e: Exception) {
        null
    }

    fun dataList(root: JsonObject): List<JsonObject> =
        root["data"]?.jsonArray?.mapNotNull { (it as? JsonObject) } ?: emptyList()

    fun imageList(root: JsonObject): List<JsonObject> =
        root["images"]?.jsonArray?.mapNotNull { (it as? JsonObject) } ?: emptyList()

    // ============ CSV 导出（仅文本数据，图片列留空） ============

    /** 导出指定条目为 CSV 文本；图片列固定留空。 */
    fun buildCsv(selected: List<Pair<String, List<JsonObject>>>): String {
        val sb = StringBuilder()
        sb.append("type,id,title,date,start,end,content,mood,priority,completed,description,extra,images\n")
        val csv = { raw: Any? ->
            val s = raw?.toString().orEmpty()
            if (s.isEmpty()) "" else "\"" + s.replace("\"", "\"\"") + "\""
        }
        for ((_, items) in selected) {
            for (o in items) {
                when (o["type"]?.let { (it as JsonPrimitive).content }) {
                    "todo" -> sb.append("待办,").append(o["id"]).append(',').append(csv(o["title"])).append(",,")
                        .append(csv(o["dueTime"])).append(",,").append(csv(o["content"])).append(',')
                        .append(csv(o["priority"])).append(',').append(csv(o["completed"])).append(',')
                        .append(csv(o["description"])).append(",,").append('\n')
                    "event" -> sb.append("日程,").append(o["id"]).append(',').append(csv(o["title"])).append(",,")
                        .append(csv(o["startTime"])).append(',').append(csv(o["endTime"])).append(',').append(csv(o["content"])).append(',')
                        .append(csv(o["color"])).append(',').append(csv(o["remindMinutes"])).append(',')
                        .append(csv(o["description"])).append(",,").append('\n')
                    "diary" -> sb.append("日记,").append(o["id"]).append(',').append(csv(o["title"])).append(',')
                        .append(csv(o["date"])).append(",,").append(csv(o["content"])).append(',')
                        .append(csv(o["mood"])).append(",,").append(csv(o["content"])).append(",\"\"\n")
                    "habit" -> sb.append("打卡,").append(o["id"]).append(',').append(csv(o["title"])).append(',')
                        .append(csv(o["checkInDates"])).append(",,").append(csv(o["targetDays"])).append('\n')
                    "countdown" -> sb.append("倒数日,").append(o["id"]).append(',').append(csv(o["title"])).append(',')
                        .append(csv(o["targetDate"])).append('\n')
                }
            }
        }
        return sb.toString()
    }

    // ============ 图片编码 ============

    /**
     * 把本地图片压缩为 ≤5MB 的 data URL（供 .mgxd images 使用）。
     * 压缩策略（严格顺序）：
     *  1) 转 JPEG，质量 80；
     *  2) 仍 >5MB → 等比例缩到长边 ≤1024；
     *  3) 仍 >5MB → 每次长边再缩 10%，直到 <5MB。
     * PNG 带透明通道时：preserveAlpha=false 默认补白再转 JPEG；true 则保留 PNG（体积更大）。
     * 需在 IO 线程调用。
     */
    fun encodeImage(path: String, preserveAlpha: Boolean): String? = try {
        val maxBytes = 5L * 1024 * 1024
        val src = BitmapFactory.decodeFile(path) ?: return null
        val hasAlpha = src.hasAlpha()
        // 是否输出带透明通道的 PNG
        val keepPng = hasAlpha && preserveAlpha
        var working: Bitmap = src
        var format = if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (keepPng) "image/png" else "image/jpeg"

        // 若需要转 JPEG 且原图有透明 → 补白
        if (hasAlpha && !keepPng) {
            val bg = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            bg.eraseColor(android.graphics.Color.WHITE)
            val cv = android.graphics.Canvas(bg)
            cv.drawBitmap(src, 0f, 0f, null)
            if (working !== src) working.recycle()
            working = bg
            src.recycle()
        }

        var longSide = maxOf(working.width, working.height)
        var scale = 1f
        if (longSide > 1024) scale = 1024f / longSide
        val out = ByteArrayOutputStream()
        var attempt = 0
        while (true) {
            out.reset()
            val target: Bitmap = if (scale < 1f) {
                val w = (working.width * scale).toInt().coerceAtLeast(1)
                val h = (working.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(working, w, h, true)
                if (scaled !== working) { working.recycle(); working = scaled }
                working
            } else working
            val quality = if (format == Bitmap.CompressFormat.JPEG) 80 else 100
            target.compress(format, quality, out)
            val size = out.size().toLong()
            attempt++
            if (size <= maxBytes || attempt >= 60 || !keepPng && scale < 0.05f) break
            scale *= 0.9f
        }
        val bytes = out.toByteArray()
        working.recycle()
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    /**
     * 把 Base64(data URL 或裸 base64) 写为本地图片文件，返回绝对路径。
     * @param bytesLimit 目标大小限制；超过先尝试降质（对超 5MB 导入兜底再压一轮）
     */
    fun decodeImageToFile(dataUrl: String, targetDir: File, fileName: String, bytesLimit: Long = 5L * 1024 * 1024): String? = try {
        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, fileName)
        val comma = dataUrl.indexOf(',')
        val meta = if (comma > 0) dataUrl.substring(0, comma) else ""
        val b64 = if (comma > 0) dataUrl.substring(comma + 1) else dataUrl
        var bytes = Base64.decode(b64, Base64.DEFAULT)
        // 若解码后仍超限：解码成 Bitmap 走压缩链降体积
        if (bytes.size > bytesLimit) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val bos = ByteArrayOutputStream()
            var q = 80
            var w = bmp.width; var h = bmp.height
            var cur = bmp
            while (q >= 20) {
                bos.reset()
                cur.compress(Bitmap.CompressFormat.JPEG, q, bos)
                if (bos.size() <= bytesLimit || q <= 20) break
                q -= 10
            }
            var scaled = cur
            var guard = 0
            while (bos.size() > bytesLimit && guard < 12) {
                w = (w * 0.9f).toInt().coerceAtLeast(64)
                h = (h * 0.9f).toInt().coerceAtLeast(64)
                val s = Bitmap.createScaledBitmap(cur, w, h, true)
                if (s !== scaled) scaled.recycle()
                scaled = s
                bos.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                guard++
            }
            bytes = bos.toByteArray()
            cur.recycle(); scaled.recycle()
            val pngHint = meta.contains("png", ignoreCase = true)
            File(target.parentFile, target.name).outputStream().use { it.write(bytes) }
            return target.absolutePath
        }
        target.outputStream().use { it.write(bytes) }
        target.absolutePath
    } catch (e: Exception) {
        null
    }

    /** 生成图片条目 refId -> 本地路径 的关系（导出用） */
    fun diaryImageRefs(d: DiaryEntity): Map<String, String> =
        d.imagePaths.mapIndexed { idx, p -> "diary_${d.id}_$idx" to p }.toMap()
}

/** 判断一个字符串是否为 .mgxd 内部图片占位符 */
fun isMgxdImageRef(value: String): Boolean = value.startsWith(MgxdCodec.REF_PREFIX)

/** 取占位符中的 refId（去掉前缀） */
fun mgxdRefId(value: String): String = value.removePrefix(MgxdCodec.REF_PREFIX)