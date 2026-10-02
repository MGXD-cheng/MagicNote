package com.magicnote.mgxd.util

import com.magicnote.mgxd.data.db.CalendarEventEntity

/**
 * 日程时间冲突对齐器
 *
 * 规则（v7.6 起，按用户要求调整）：
 * 1) **优先压缩更早的日程**：若新日程「跨进」了某个开始时间更早的日程
 *    （已有日程 start ≤ 新日程 start < 已有日程 end），
 *    则把那个更早日程的**结束时间对齐到新日程的开始时间**（开始早的日程的结束时间 = 下一个日程的开始时间）。
 *    需要被改写的日程通过 [Result.shrunken] 返回，由调用方落库。
 * 2) **兜底顺延**：若新日程仍然与其它日程重叠（例如它覆盖了后面某个日程的开始），
 *    则新日程自身顺延到冲突日程结束之后，时长保持不变（最多 50 次，防死循环）。
 */
object EventConflictResolver {

    /** 对齐结果：新日程最终时间 + 需要被缩短结束时间的已有日程 */
    data class Result(
        val start: Long,
        val end: Long,
        val shrunken: List<CalendarEventEntity>
    )

    /** 解析不冲突的 [start, end)。excludeId 用于编辑时排除自身。 */
    fun resolve(
        start: Long,
        end: Long,
        events: List<CalendarEventEntity>,
        excludeId: Long = -1L
    ): Result {
        val duration = (end - start).coerceAtLeast(1L)

        // ① 把「跨过新日程开始」的更早日程，结束时间对齐到新日程开始
        val shrunken = ArrayList<CalendarEventEntity>()
        events.filter { ev ->
            ev.id != excludeId && ev.startTime <= start && ev.endTime > start
        }.forEach { ev ->
            shrunken.add(ev.copy(endTime = start))
        }

        // ② 新日程自身仍需避让其它日程（已被缩短的那些不再参与）
        var s = start
        var e = start + duration
        var guard = 0
        while (guard++ < MAX_PASSES) {
            val clash = events.firstOrNull { ev ->
                ev.id != excludeId &&
                    s < ev.endTime && ev.startTime < e &&
                    !(ev.startTime <= start && ev.endTime > start)
            }
            if (clash == null) break
            s = clash.endTime
            e = s + duration
        }
        return Result(s, e, shrunken)
    }

    private const val MAX_PASSES = 50
}