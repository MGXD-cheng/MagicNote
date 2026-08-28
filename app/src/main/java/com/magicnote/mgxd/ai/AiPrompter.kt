package com.magicnote.mgxd.ai

import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.CountdownEntity
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Magic AI 提示词构建器
 *
 * ========== DeepSeek 上下文缓存优化说明（重要，勿随意打乱结构） ==========
 * DeepSeek 按「请求前缀」自动缓存：只要 messages 的开头部分与缓存一致，命中后
 * 费用大幅降低、速度更快。因此这里刻意保持：
 *
 * 1. `<system>` 块 = 人格 + 固定规则，**永远不变**
 *    → 是所有请求共享的缓存前缀，命中率最高的一块
 * 2. `<context>` 块 = 当日动态数据，**必须放最后**
 *    → 缓存只在数据变化的位置截断，尽量保住 system 前缀
 *
 * 同理，buildTodoParsePrompt / buildDailySummaryPrompt 也把固定模板放最前。
 * 所有动态文本都经过 XML 转义，防止破坏标签结构导致前缀漂移。
 */
object AiPrompter {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // 用 ASCII 码构造引号字符，避免源码中出现转义引号
    private val DQUOTE: Char = 34.toChar()
    private val SQUOTE: Char = 39.toChar()

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun Long.toTime(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(timeFmt)

    private fun Long.toDateStr(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(dateFmt)

    /** 转义 XML 特殊字符，防止用户数据破坏标签结构（保证前缀稳定 = 缓存命中） */
    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(DQUOTE.toString(), "&quot;")
            .replace(SQUOTE.toString(), "&apos;")

    /**
     * 渲染待办 XML（动态区）
     * enabled=false 且历史有数据时只注入「隐藏摘要」（不泄露详情，但让 AI 知道历史存在），
     * 避免 AI 误以为「从未记录过」；无历史数据时标记 disabled。
     */
    private fun renderTodos(todos: List<TodoEntity>, enabled: Boolean = true): String = buildString {
        if (!enabled) {
            if (todos.isEmpty()) {
                append("<todo status='disabled' note='待办功能已在设置中关闭，无历史数据' />")
                return@buildString
            }
            val latest = todos.maxByOrNull { it.createdAt }
            val aiCount = todos.count { it.source == "magic_ai" }
            append("<todo status='hidden' count='")
            append(todos.size)
            append("' ai_created='")
            append(aiCount)
            append("' latest='")
            append(xmlEscape(latest?.title ?: String()))
            append("' note='待办功能已关闭，历史数据存在但未展示详情；用户询问时如实告知' />")
            return@buildString
        }
        if (todos.isEmpty()) {
            append("<todo status='empty' />")
            return@buildString
        }
        todos.forEach { t ->
            val status = if (t.completed) "done" else "pending"
            val type = if (t.isLongTerm) "long_term" else "today"
            val due = t.dueTime?.let { " due='" + it.toDateStr() + " " + it.toTime() + "'" } ?: String()
            val pri = when (t.priority) {
                0 -> "low"; 2 -> "high"; else -> "medium"
            }
            append("    <todo type='")
            append(type)
            append("' status='")
            append(status)
            append("' priority='")
            append(pri)
            append("'")
            append(due)
            append(">")
            append(xmlEscape(t.title))
            appendLine("</todo>")
        }
    }

    /**
     * 渲染日程 XML（动态区，带完整日期，防止 AI 把历史日程误判为今天）
     * enabled=false 时同 renderTodos：有历史则注入 hidden 摘要，无历史标记 disabled。
     */
    private fun renderEvents(events: List<CalendarEventEntity>, enabled: Boolean = true): String = buildString {
        if (!enabled) {
            if (events.isEmpty()) {
                append("<event status='disabled' note='日历功能已在设置中关闭，无历史数据' />")
                return@buildString
            }
            val latest = events.maxByOrNull { it.createdAt }
            val aiCount = events.count { it.source == "magic_ai" }
            append("<event status='hidden' count='")
            append(events.size)
            append("' ai_created='")
            append(aiCount)
            append("' latest='")
            append(xmlEscape(latest?.title ?: String()))
            append("' note='日历功能已关闭，历史数据存在但未展示详情；用户询问时如实告知' />")
            return@buildString
        }
        if (events.isEmpty()) {
            append("<event status='empty' />")
            return@buildString
        }
        events.forEach { e ->
            append("    <event date='")
            append(e.startTime.toDateStr())
            append("' start='")
            append(e.startTime.toTime())
            append("' end='")
            append(e.endTime.toTime())
            append("'>")
            append(xmlEscape(e.title))
            appendLine("</event>")
        }
    }

    /** 渲染日记 XML（动态区；enabled=false 时只注入 hidden/disabled 摘要） */
    private fun renderDiaries(diaries: List<DiaryEntity>, enabled: Boolean = true): String = buildString {
        if (!enabled) {
            if (diaries.isEmpty()) {
                append("<diary status='disabled' note='日记功能已在设置中关闭，无历史数据' />")
                return@buildString
            }
            val latest = diaries.maxByOrNull { it.date }
            append("<diary status='hidden' count='")
            append(diaries.size)
            append("' latest_date='")
            append((latest?.date ?: 0L).toDateStr())
            append("' note='日记功能已关闭，历史数据存在但未展示详情；用户询问时如实告知' />")
            return@buildString
        }
        if (diaries.isEmpty()) {
            append("<diary status='empty' />")
            return@buildString
        }
        diaries.take(3).forEach { d ->
            val mood = when (d.mood) {
                0 -> "😞"; 1 -> "😐"; 2 -> "🙂"; 3 -> "😄"; 4 -> "🤩"; else -> "🙂"
            }
            val preview = xmlEscape(d.content.replace("\n", " ").take(60))
            val more = if (d.content.length > 60) "…" else String()
            append("    <diary date='")
            append(d.date.toDateStr())
            append("' mood='")
            append(mood)
            append("'>")
            append(preview)
            append(more)
            appendLine("</diary>")
        }
    }

    /** 渲染屏幕时间 XML（动态区，可选；授权后才有数据） */
    private fun renderScreenTime(summary: String?): String =
        if (summary.isNullOrBlank()) {
            "    <screen_time status='none' />"
        } else {
            "    <screen_time>" + xmlEscape(summary.trim()) + "</screen_time>"
        }

    /**
     * 构建带上下文的系统提示词（XML 结构化 + 缓存友好）
     * 固定前缀：XML 声明 + `<system>` 块 → 所有请求共享缓存
     */
    fun buildSystemPrompt(
        personality: Personality,
        customPrompt: String,
        todos: List<TodoEntity>,
        events: List<CalendarEventEntity>,
        recentDiaries: List<DiaryEntity>,
        screenTimeSummary: String? = null,
        todoEnabled: Boolean = true,
        calendarEnabled: Boolean = true,
        diaryEnabled: Boolean = true
    ): String {
        val base = if (customPrompt.isNotBlank()) customPrompt else personality.systemPrompt
        return buildString {
            // ===== 固定前缀（缓存命中区）=====
            appendLine("<?xml version='1.0' encoding='UTF-8'?>")
            appendLine("<magic_note version='1'>")
            appendLine("  <system>")
            append("    <role>你是「Magic note」的 Magic AI 生活助手。")
            append(xmlEscape(base.replace('\n', ' ')))
            appendLine("</role>")
            appendLine("    <rules>")
        appendLine("      <rule>回答简洁温暖，默认使用中文。</rule>")
        appendLine("      <rule>只能引用下方 context 中真实存在的数据，禁止捏造待办、日程或日记。</rule>")
        appendLine("      <rule>用户询问安排时，主动给出优先级建议。</rule>")
        appendLine("      <rule>屏幕时间数据可用于分析使用习惯：给出健康提醒、娱乐/学习/社交占比观察、休息建议等（仅引用真实数据）。</rule>")
        appendLine("      <rule>用纯文本回答，不要输出 XML 标签。</rule>")
        appendLine("      <rule>如果用户要求创建/记录日程或待办，而下方 context 里没有出现新增项（说明自动创建未成功），必须如实回复「抱歉，自动创建没有成功」，并建议用户用简洁句式再试一次（例如：明天下午3点去医院），绝不能假装已经记下。</rule>")
        appendLine("      <rule>如果用户要求使用已在设置中关闭的功能（待办/日历/日记，见下方 module 状态 off），必须明确回复「该功能已关闭，请到设置中开启」，不要执行、不要假装已执行。</rule>")
        appendLine("      <rule>如果用户询问之前记录/创建的待办、日程或日记：即使对应功能当前已关闭，只要下方 context 中该功能标记为 hidden（历史数据存在），就必须如实告知历史存在（数量、最新一条、其中几条由 magic ai 创建），并说明该功能现已在设置中关闭、开启后即可查看；绝不能声称从未记录过。若标记为 disabled（无历史数据）才能说「之前没有记录过」。</rule>")
        appendLine("    </rules>")
            appendLine("  </system>")
            appendLine()
            // ===== 动态区（缓存在此截断）=====
            appendLine("  <context>")
            append("    <module todo='")
            append(if (todoEnabled) "on" else "off")
            append("' calendar='")
            append(if (calendarEnabled) "on" else "off")
            append("' diary='")
            append(if (diaryEnabled) "on" else "off")
            appendLine("' />")
            append("    <date>")
            append(System.currentTimeMillis().toDateStr())
            append(" ")
            append(System.currentTimeMillis().toTime())
            appendLine("</date>")
            appendLine("    <todos>")
            append(renderTodos(todos, todoEnabled))
            appendLine()
            appendLine("    </todos>")
            appendLine("    <events>")
            append(renderEvents(events, calendarEnabled))
            appendLine()
            appendLine("    </events>")
            appendLine("    <diaries>")
            append(renderDiaries(recentDiaries, diaryEnabled))
            appendLine()
            appendLine("    </diaries>")
            appendLine(renderScreenTime(screenTimeSummary))
            appendLine("  </context>")
            appendLine("</magic_note>")
        }
    }

    /**
     * 功能 A：把一句自然语言解析成结构化待办（返回 JSON）
     * 固定模板放最前（缓存前缀），用户输入放最后（动态）
     */
    fun buildTodoParsePrompt(userText: String, now: LocalDateTime): String = buildString {
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<todo_parser version='1'>")
        appendLine("  <instruction>")
        appendLine("    把用户输入的自然语言解析为待办事项 JSON，只输出 JSON，不要任何解释或 Markdown。")
        append("    当前时间：")
        append(now.format(dateTimeFmt))
        append("（")
        append(weekday)
        appendLine("）")
        appendLine("    输出格式：{'title':'简短标题，必填','description':'备注，没有则为空字符串','priority':0|1|2,'dueTime':'yyyy-MM-ddTHH:mm:ss'或 null,'remindAt':'yyyy-MM-ddTHH:mm:ss'或 null}")
        appendLine("    注意：实际输出的 JSON 必须使用标准双引号，这里用单引号仅为示意。")
        appendLine("    规则：")
        appendLine("      1. 相对时间（明天/后天/下午3点/下周X）要换算成绝对时间；")
        appendLine("      2. priority：0=低，1=中，2=高；")
        appendLine("      3. 没有截止时间时 dueTime 为 null；")
        appendLine("      4. remindAt 默认比 dueTime 提前 10 分钟，无法确定就为 null；")
        appendLine("      5. 所有时间都必须是当前时间之后。")
        appendLine("  </instruction>")
        append("  <user_input>")
        append(xmlEscape(userText))
        appendLine("</user_input>")
        appendLine("</todo_parser>")
    }

    /**
     * 功能 C：每日简报（真 AI 文案）
     * 固定模板（任务+规则）放最前 → 同一人格下共享缓存前缀；统计数据与上下文放最后
     */
    fun buildDailySummaryPrompt(
        personality: Personality,
        completed: Int,
        active: Int,
        overdueTitles: List<String>,
        todos: List<TodoEntity>,
        events: List<CalendarEventEntity>,
        diaries: List<DiaryEntity>
    ): String = buildString {
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<daily_summary version='1'>")
        append("  <task>以「")
        append(xmlEscape(personality.label))
        append("」的口吻（")
        append(personality.emoji)
        appendLine("），为用户生成今日总结与鼓励文案。</task>")
        appendLine("  <rules>")
        appendLine("    <rule>总长度不超过 60 字，口语化，适合通知栏展示。</rule>")
        appendLine("    <rule>有未完成任务时给出 1 条具体建议；全部完成时真诚庆祝。</rule>")
        appendLine("    <rule>只引用下方真实数据，不编造。</rule>")
        appendLine("  </rules>")
        appendLine("  <stats>")
        append("    <completed>")
        append(completed.toString())
        appendLine("</completed>")
        append("    <active>")
        append(active.toString())
        appendLine("</active>")
        append("    <overdue>")
        append(xmlEscape(overdueTitles.take(3).joinToString("、")))
        appendLine("</overdue>")
        appendLine("  </stats>")
        appendLine("  <context>")
        append("    <date>")
        append(System.currentTimeMillis().toDateStr())
        appendLine("</date>")
        appendLine("    <todos>")
        append(renderTodos(todos))
        appendLine()
        appendLine("    </todos>")
        appendLine("    <events>")
        append(renderEvents(events))
        appendLine()
        appendLine("    </events>")
        appendLine("    <diaries>")
        append(renderDiaries(diaries))
        appendLine()
        appendLine("    </diaries>")
        appendLine("  </context>")
        appendLine("</daily_summary>")
    }

    /**
     * 每日进度汇总（模板兜底）：AI 调用失败时降级使用，保证通知永不缺席
     */
    fun buildDailySummary(
        personality: Personality,
        completed: Int,
        active: Int,
        overdueTitles: List<String>
    ): String {
        val total = completed + active
        return when {
            total == 0 -> personality.emoji + " 今天没有待办任务，享受轻松的一天吧！"
            active == 0 -> personality.emoji + " 太棒了！今天 " + total + " 项任务全部完成！给努力的自己点个赞！"
            overdueTitles.isNotEmpty() -> {
                val sample = overdueTitles.take(3).joinToString("、")
                val more = if (overdueTitles.size > 3) "等 " + overdueTitles.size + "项" else String()
                personality.urgeStyle.replace("{n}", active.toString()) +
                    "\n已超时未完成：" + sample +
                    (if (more.isNotBlank()) " " else String()) + more
            }
            else -> "今日进度：已完成 " + completed + "/" + total + " 项，还剩 " + active + " 项。\n" +
                personality.urgeStyle.replace("{n}", active.toString())
        }
    }

    /**
     * 待办未完成：AI 定时催促提醒（后台服务每 ≥1 小时触发一次）
     * 固定模板放最前，动态待办放最后，保持缓存友好
     */
    fun buildTodoUrgePrompt(
        personality: Personality,
        todos: List<TodoEntity>
    ): String = buildString {
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<todo_urge version='1'>")
        append("  <task>以「")
        append(xmlEscape(personality.label))
        append("」人格（")
        append(personality.emoji)
        appendLine("）的口吻，提醒用户去完成待办事项。</task>")
        appendLine("  <rules>")
        appendLine("    <rule>不超过 80 字，口语化、有温度，适合通知栏展示。</rule>")
        appendLine("    <rule>挑最重要（高优先级或临近截止）的 1-2 项重点催促，其他一笔带过。</rule>")
        appendLine("    <rule>只引用下方真实待办，不编造；不要用列表符号。</rule>")
        appendLine("  </rules>")
        appendLine("  <context>")
        appendLine("    <todos>")
        append(renderTodos(todos))
        appendLine()
        appendLine("    </todos>")
        appendLine("  </context>")
        appendLine("</todo_urge>")
    }

    /**
     * 屏幕时间：娱乐超时提醒（真 AI 文案）
     * 固定模板放最前，动态数据放最后，保持缓存友好
     */
    fun buildEntertainmentWarnPrompt(
        personality: Personality,
        appLabel: String,
        minutes: Int,
        todos: List<TodoEntity>
    ): String = buildString {
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<entertainment_warn version='1'>")
        append("  <task>用一句话提醒用户休息，语气符合「")
        append(xmlEscape(personality.label))
        append("」人格（")
        append(personality.emoji)
        appendLine("）。</task>")
        appendLine("  <rules>")
        appendLine("    <rule>不超过 50 字，口语化，适合通知栏展示。</rule>")
        append("    <rule>要提到「")
        append(xmlEscape(appLabel))
        append("」和已连续使用 ")
        append(minutes.toString())
        appendLine(" 分钟。</rule>")
        appendLine("    <rule>若下方有待办，顺带提醒 1 条；没有则鼓励休息放松。</rule>")
        appendLine("    <rule>只引用下方真实待办，不编造。</rule>")
        appendLine("  </rules>")
        appendLine("  <context>")
        appendLine("    <todos>")
        append(renderTodos(todos))
        appendLine()
        appendLine("    </todos>")
        appendLine("  </context>")
        appendLine("</entertainment_warn>")
    }

    /**
     * 日历页 AI 规划：根据需求/资源/截止时间/优先级生成可执行计划方案（返回 JSON）
     * 固定模板放最前，动态输入与现有数据放最后
     */
    fun buildPlanPrompt(
        requirement: String,
        resources: String,
        deadline: Long,
        priorityLabel: String,
        todos: List<TodoEntity>,
        events: List<CalendarEventEntity>
    ): String = buildString {
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<plan_parser version='1'>")
        appendLine("  <instruction>")
        appendLine("    用户想让你帮忙规划一个计划。请分析需求、现有资源、截止时间与优先级，输出一套可执行的计划方案 JSON。只输出 JSON，不要任何解释或 Markdown。")
        append("    当前时间：")
        append(System.currentTimeMillis().toDateStr())
        append(" ")
        append(System.currentTimeMillis().toTime())
        appendLine()
        appendLine("    现有待办（避免重复安排，最多列出 20 条未完成的）：")
        todos.filter { !it.completed }.take(20).forEach { t ->
            append("      - id=")
            append(t.id)
            append(" title=")
            append(xmlEscape(t.title))
            t.dueTime?.let { append(" due=" + it.toDateStr() + " " + it.toTime()) }
            appendLine()
        }
        appendLine("    现有日程（避免时间冲突，最多列出 30 条未来的）：")
        val now = System.currentTimeMillis()
        events.filter { it.startTime >= now - 24 * 60 * 60 * 1000L }.take(30).forEach { e ->
            append("      - id=")
            append(e.id)
            append(" title=")
            append(xmlEscape(e.title))
            append(" start=")
            append(e.startTime.toDateStr())
            append(" ")
            append(e.startTime.toTime())
            appendLine()
        }
        appendLine("    输出格式（标准双引号 JSON）：")
        appendLine("    {'planTitle':'计划标题','summary':'方案一句话说明','steps':[{'type':'event'|'todo','title':'步骤名','startTime':'yyyy-MM-ddTHH:mm:ss'或null,'endTime':'yyyy-MM-ddTHH:mm:ss'或null,'remindMinutes':30,'dueTime':'yyyy-MM-ddTHH:mm:ss'或null,'priority':0|1|2}]}")
        appendLine("    规则：")
        appendLine("      1. 把大目标拆成 3~8 个有序步骤，每一步都是当天能完成的行动；")
        appendLine("      2. event=有具体时间段的行动（学习/会议/运动/外出），startTime 必填、endTime 默认 startTime+1小时、remindMinutes 从 10/30/60 中选（可 0=不提醒）；")
        appendLine("      3. todo=纯任务（无固定时段），dueTime 必填、priority 0低/1中/2高（结合用户优先级与步骤重要性）；")
        appendLine("      4. 所有步骤必须排在截止时间之前，按先后顺序均匀分布，不要全部堆到截止前一天；")
        appendLine("      5. 结合现有日程/待办，避免重复安排或明显时间冲突；")
        appendLine("      6. steps 不能为空数组。")
        appendLine("      7. 【时长估算】先评估每个步骤/模块大概需要多长时间（容量判断），再倒推安排时间：宁可排得务实留有余量，也不要虚排一个根本完不成的时长；例如'三角函数+数列+基本初等函数'这种大块内容，至少拆成多个独立步骤或给足 90 分钟以上，不要塞进 70 分钟；")
        appendLine("      8. 【休息与缓冲】相邻两个学习/工作步骤之间强制插入 10 分钟休息，或采用番茄钟节奏（学 50 分钟休 10 分钟）；一天的安排必须'留白'，不要排满每一分钟，留出意外缓冲；休息可作为单独的 event 步骤（type=event，标题如'休息'，时长 10 分钟，remindMinutes=0），也可以不输出而直接在每个步骤后留出空档（startTime 与下一步的 startTime 之间自然隔开）；")
        appendLine("      9. 【精力曲线】按一天精力高低安排内容：上午精力最好→安排最烧脑/需要高度集中的内容（如数学、物理、编程、写作）；下午容易犯困→安排记忆类/整理类/机械性内容（如化学错题整理、生物背诵、笔记归纳、抄写）；晚上→轻量内容与复盘总结。如果用户已说明某个时段不方便（如下午容易刷手机），不要在那个时段安排高难度内容；")
        appendLine("      10. 【优先级驱动】先按优先级从高到低排列步骤（结合用户给定的整体优先级与各步骤的重要性），再为每个步骤分配时间，而不是机械地按学科/科目顺序硬排；")
        appendLine("      11. 输出中每个 event 步骤的 startTime/endTime 必须真实可执行且与第 7~10 条规则自洽；休息空档可体现在相邻步骤的时间间隔上。")
        appendLine("  </instruction>")
        appendLine("  <user_input>")
        appendLine("    需求：")
        append(xmlEscape(requirement))
        appendLine()
        appendLine("    现有资源：")
        append(xmlEscape(resources))
        appendLine()
        appendLine("    截止时间：")
        append(deadline.toDateStr())
        appendLine()
        appendLine("    优先级：")
        append(xmlEscape(priorityLabel))
        appendLine()
        appendLine("  </user_input>")
        appendLine("</plan_parser>")
    }

    /**
     * 功能 E：解析用户对日程/待办/打卡/倒数日的增删改指令（返回 JSON）
     * 固定模板放最前（缓存前缀），用户输入与数据列表放最后（动态）
     */
    fun buildCommandParsePrompt(
        userText: String,
        now: LocalDateTime,
        todos: List<TodoEntity>,
        events: List<CalendarEventEntity>,
        countdowns: List<CountdownEntity> = emptyList(),
        habits: List<HabitEntity> = emptyList(),
        todoEnabled: Boolean = true,
        calendarEnabled: Boolean = true
    ): String = buildString {
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<command_parser version='2'>")
        appendLine("  <instruction>")
        appendLine("    判断用户输入是否是要「新增/编辑/删除」待办、日程、每日打卡或倒数日。只输出 JSON，不要任何解释或 Markdown。")
        appendLine("    功能开关状态：待办=" + if (todoEnabled) "开" else "关" + "，日历=" + if (calendarEnabled) "开" else "关" + "（关=该功能已在设置中禁用；打卡/倒数日属于待办模块）。")
        append("    当前时间：")
        append(now.format(dateTimeFmt))
        append("（")
        append(weekday)
        appendLine("）")
        appendLine("    待办列表（id 用于定位目标）：")
        todos.forEach { t ->
            append("      - id=")
            append(t.id)
            append(" title=")
            append(xmlEscape(t.title))
            append(" type=")
            append(if (t.isLongTerm) "长期" else "今日")
            append(" done=")
            append(t.completed)
            t.dueTime?.let { append(" due=" + it.toDateStr() + " " + it.toTime()) }
            appendLine()
        }
        appendLine("    日程列表：")
        events.forEach { e ->
            append("      - id=")
            append(e.id)
            append(" title=")
            append(xmlEscape(e.title))
            append(" start=")
            append(e.startTime.toDateStr())
            append(" ")
            append(e.startTime.toTime())
            append(" end=")
            append(e.endTime.toTime())
            appendLine()
        }
        appendLine("    每日打卡列表：")
        habits.forEach { h ->
            append("      - id=")
            append(h.id)
            append(" title=")
            append(xmlEscape(h.title))
            h.remindHour.let { if (it >= 0) append(" remind=" + String.format("%02d:%02d", it, h.remindMinute)) }
            if (h.targetDays > 0) append(" target=" + h.targetDays + "天")
            appendLine()
        }
        appendLine("    倒数日列表：")
        countdowns.forEach { c ->
            append("      - id=")
            append(c.id)
            append(" title=")
            append(xmlEscape(c.title))
            append(" target=")
            append(c.targetDate.toDateStr())
            appendLine()
        }
        appendLine("    输出格式（标准双引号 JSON）：")
        appendLine("    {'actions':[{'action':'create_todo','targetId':0,'title':'标题','description':'备注','dueTime':'yyyy-MM-ddTHH:mm:ss'或null,'remindAt':'yyyy-MM-ddTHH:mm:ss'或null,'priority':1,'isLongTerm':false,'startTime':'yyyy-MM-ddTHH:mm:ss'或null,'endTime':'yyyy-MM-ddTHH:mm:ss'或null,'color':4279591935,'targetDate':'yyyy-MM-dd'或null,'remindTime':'HH:mm'或null,'targetDays':21或0}],'summary':'给用户的一句话汇总确认'}")
        appendLine("    规则：")
        appendLine("      1. 用户一句话里可能包含多个信息（如「明天下午3点去医院，再建个每天背单词的打卡」），要全部提炼成 actions 数组里的多个对象；")
        appendLine("      2. action 是编辑/删除时，必须从上方列表挑出匹配的 id 填 targetId（按标题匹配）；找不到目标则该项不要放进 actions；")
        appendLine("      3. 编辑待办/日程时，未提到的字段保持原值；")
        appendLine("      4. 相对时间（明天/下午3点/下周X）换算成绝对时间；所有时间必须是当前时间之后；")
        appendLine("      ★ 打卡 vs 倒数日 vs 待办/日程 判别（最高优先级）：")
        appendLine("        - 「每天/每周固定做某事」「养成习惯」「坚持每天」→ create_habit（每日打卡），如「每天背20个单词」「坚持每天跑步」；有具体提醒时间（早上8点/晚上9点）就填 remindTime='HH:mm'，没有则 null；提到目标天数（21天/30天/100天）填 targetDays，没有填 0；")
        appendLine("        - 「距离XX还有N天」「XX倒计时」「XX纪念日/生日/高考/考试/婚礼」→ create_countdown（倒数日），targetDate 填目标日期 'yyyy-MM-dd'，如「距离高考还有78天」→ targetDate 为实际高考日期；")
        appendLine("        - 具体某天的安排 → 按下方原规则 create_event/create_todo；")
        appendLine("        - 拿不准时：每日重复 → create_habit，未来某天 → create_countdown，当天/某天具体时间 → create_event，纯任务无时间 → create_todo。")
        appendLine("      5. 待办 vs 日程 判别（create_habit/create_countdown 之外）：")
        appendLine("        - 用户说「日程/日历/会议/预约/几点/什么时候」或给出具体时间点（明天下午3点、下周一9点、今晚8点）→ 一律 create_event（日程，startTime 必填）；")
        appendLine("        - 「某天/某时段做某事」即使没说'日程'二字也建 create_event：如「明天去立仁学习一天」「下周一开会」「周末爬山」「明天下午去医院复查」；")
        appendLine("        - 用户说「待办/任务/事项/记得做/别忘了」或只是没有具体时间的任务清单（买牛奶、交作业）→ create_todo（待办）；")
        appendLine("        - 拿不准时：有具体时间或时间段 → create_event，纯任务无时间 → create_todo；绝不要把有具体时间的安排建成待办。")
        appendLine("      6. 新建待办 remindAt 默认比 dueTime 提前 10 分钟；新建日程 startTime 必填、endTime 默认 startTime+1小时（整天活动如'学习一天'可填当天 08:00-22:00）；")
        appendLine("      7. priority：0=低 1=中 2=高；color 为日程颜色 ARGB 整数（可省略用 0）；")
        appendLine("      8. 普通聊天（不是操作指令）actions 返回空数组 []；")
        appendLine("      9. summary 用中文一句话汇总所有操作（如：已创建「医院复查」日程和「背单词」每日打卡）。")
        appendLine("      10. 用户要求操作已关闭的功能（上方标注为「关」）→ 该类型 actions 一律不输出（如日历已关就绝不输出 create_event/edit_event/delete_event；待办已关就绝不输出 create_todo/create_habit/create_countdown 等）。")
        appendLine("  </instruction>")
        append("  <user_input>")
        append(xmlEscape(userText))
        appendLine("</user_input>")
        appendLine("</command_parser>")
    }

    /**
     * 屏幕时间：日/周报告润色（真 AI 总结）
     * 固定模板放最前，动态数据放最后，保持缓存友好
     */
    fun buildScreenReportPrompt(
        personality: Personality,
        reportType: String,
        reportText: String
    ): String = buildString {
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<screen_report version='1'>")
        append("  <task>你是 Magic note 的 Magic AI，请以「")
        append(xmlEscape(personality.label))
        append("」人格（")
        append(personality.emoji)
        appendLine("）的语气，把下方的")
        append(xmlEscape(reportType))
        appendLine("润色成一段更生动、口语化、适合通知栏展示的总结。</task>")
        appendLine("  <rules>")
        appendLine("    <rule>保留关键数据（时长、占比、最多的应用），不得虚构。</rule>")
        appendLine("    <rule>不超过 120 字，分 2-3 行，开头用一句总结。</rule>")
        appendLine("    <rule>可适当调侃/鼓励，符合人格，不要用列表符号。</rule>")
        appendLine("  </rules>")
        appendLine("  <context>")
        appendLine("    <report_data>")
        appendLine(xmlEscape(reportText))
        appendLine("    </report_data>")
        appendLine("  </context>")
        appendLine("</screen_report>")
    }

    /**
     * 日记自动回复：AI 针对用户刚写完的日记生成回复（共情 / 建议 / 鼓励）
     * 固定模板放最前，日记内容放最后（缓存友好）
     */
    fun buildDiaryReplyPrompt(
        personality: Personality,
        diaryTitle: String?,
        diaryContent: String,
        mood: Int,
        dateStr: String
    ): String = buildString {
        appendLine("<?xml version='1.0' encoding='UTF-8'?>")
        appendLine("<diary_reply version='1'>")
        append("  <task>用户刚写完一篇日记，请以「")
        append(xmlEscape(personality.label))
        append("」人格（")
        append(personality.emoji)
        appendLine("）的口吻，给出一段真挚的回复。</task>")
        appendLine("  <rules>")
        appendLine("    <rule>长度 60~120 字，口语化、有温度，像朋友聊天一样自然。</rule>")
        appendLine("    <rule>先共情（认同感受），再给 1 条具体的小建议或温暖的鼓励；不要空洞说教。</rule>")
        appendLine("    <rule>只基于下方日记内容回复，不虚构、不编造。</rule>")
        appendLine("    <rule>日记可能包含私密内容，回复要尊重与体贴，不评判。</rule>")
        appendLine("  </rules>")
        appendLine("  <context>")
        append("    <date>")
        append(xmlEscape(dateStr))
        appendLine("</date>")
        append("    <mood>")
        append(mood)
        appendLine("</mood>")
        append("    <title>")
        append(xmlEscape(diaryTitle ?: String()))
        appendLine("</title>")
        appendLine("    <content>")
        append(xmlEscape(diaryContent))
        appendLine("</content>")
        appendLine("  </context>")
        appendLine("</diary_reply>")
    }
}