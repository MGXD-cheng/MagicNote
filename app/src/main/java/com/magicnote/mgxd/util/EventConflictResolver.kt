package com.magicnote.mgxd.util

import com.magicnote.mgxd.data.db.CalendarEventEntity

/**
 * 日程时间冲突对齐器
 *
 * 规则：新增/编辑日程时，若与已有日程时间重叠，后一个日程自动顺延到冲突日程结束之后，
 * 时长保持不变；若顺延后仍与其他日程冲突，继续顺延直到无冲突（最多 50 次防死循环）。
 */
object EventConflictResolver {

    /** 解析不冲突的 [start, end)；冲突时自动顺延并返回调整后的时间。excludeId 用于编辑时排除自身。 */
    fun resolve(
        start: Long,
        end: Long,
        events: List<CalendarEventEntity>,
        excludeId: Long = -1L
    ): Pair<Long, Long> {
        val duration = (end - start).coerceAtLeast(1L)
        var s = start
        var e = start + duration
        var guard = 0
        while (guard++ < MAX_PASSES) {
            val clash = events.firstOrNull { ev ->
                ev.id != excludeId && s < ev.endTime && ev.startTime < e
            }
            if (clash == null) break
            // 顺延到冲突日程结束之后
            s = clash.endTime
            e = s + duration
        }
        return s to e
    }

    private const val MAX_PASSES = 50
}
