# Magic note

一款集待办、日历、日记与 AI 助手于一体的 Android 效率应用。
Jetpack Compose + Kotlin 开发，包名 com.magicnote.mgxd，当前版本 6.4。

## 功能

- 待办：今日/长期双类型，AI 一句话建待办，点击即编辑，到期提醒。
- 日历：日程事件管理，按天查看。
- 日记：同一天可记多篇，按时间线合并展示，支持心情标记与编辑。
- Magic AI：DeepSeek 驱动的对话助手，历史记录持久化并标注时间；可分析屏幕时间、生成待办催促与日报/周报。
- 屏幕时间：基于系统 UsageStats 统计使用时长，分类环形图展示，日/周 AI 报告；娱乐超时自动 AI 提醒。
- 通知体系：待办提醒、每日汇总、屏幕时间、报告、AI 提醒、后台守护六个渠道。
- 后台保活：前台服务 + AlarmManager 兜底，节能策略（息屏不检查、亮屏 5 分钟一次）。
- 隐私：所有数据仅存本机，不上传。

## 技术栈

- Jetpack Compose + Material 3，100% Kotlin
- Room 数据库（todos / diaries / events / chat_messages 四表）
- DataStore 偏好存储，Navigation Compose
- OkHttp + DeepSeek API（XML 结构化提示词，前缀缓存友好）
- AlarmManager 精确调度 + 前台服务保活
- Gradle Version Catalog 依赖管理

## 版本历史

- v3.1：建立版本号体系与 APK 命名规则（MagicNote-版本号），设置页显示版本号。
- v3.2：屏幕时间全套功能上线——使用时长统计、娱乐超时 AI 提醒、主页分类图表、日（22点）/周（周日8点）AI 报告、应用分类自定义；Magic AI 注入屏幕时间数据可分析。
- v3.3：修复娱乐提醒不触发（保活轮询替代不可靠的 Alarm）；待办未完成时 AI 每小时催促；新增后台保活前台服务。
- v3.4：节能模式——息屏不检查、亮屏 5 分钟检查一次、无待办不检查。
- v3.5：待办双类型（今日待办默认 / 长期待办可切换）。
- v3.6：首页今日待办全量显示；待办点击编辑；AI 催促智能调度（夜间 22-06 静默、今日全完成只剩长期待办则每晚 20 点一次）。
- v3.7：代码重构——ViewModel 拆分、TimeUtils 公共工具、AiClient 共享连接池、通知/调度去重；发行版打包方案落地（v3.7-release）。
- v3.8：稳定性修复——屏幕监控节流消除双重触发、协程取消安全模式（CancellationException 正确透传）、广播总超时兜底，解决偶发无响应（ANR）。
- v3.9：应用包名改为 com.magicnote.mgxd（原 com.linxitech.assistant）。
- v4.0：每日 0 点自动清理已完成的昨日待办，未完成的红字标注「未完成」；新增纯净模式（一键关闭后台保活与所有提醒）；修复每日 22:00 屏幕报告不通知（rescheduleAll 被 collect 挂起导致日/周报告闹钟从未调度）。
- v4.1：日程支持点击编辑；Magic AI 可直接增删改日程与待办（对话指令解析）；日程时间冲突自动顺延对齐（后一个日程排到冲突结束之后，含 Toast 提示）。
- v4.2：日记支持添加本地图片（保存到应用私有目录 diary_images/，不联网）；新增功能模块开关（待办/日历/日记可独立关闭，关闭后底部导航与首页对应入口/卡片隐藏，待办关闭时今日任务进度一并隐藏；数据库 v4 迁移 diaries 表新增 imagePaths 列）。
- v4.3：待办关闭时首页 Magic AI 今日提示一并隐藏；功能模块仅剩 0 或 1 个时隐藏「今日」聚合首页入口，App 直接进入剩余功能。
- v4.4：设置改为底部导航常驻「设置」tab（不受功能模块开关影响，首页隐藏时设置入口依然可用）；首页右上角设置按钮改为直接跳转设置 tab；设置关闭后回到第一个可见功能页。
- v4.5：修复 ReminderReceiver/BootReceiver exported=false 导致所有闹钟（每日汇总/日报告/待办提醒/开机恢复）收不到系统广播的严重 bug；AI 注入日程带完整日期防误判；每日汇总时自动删除过期且已完成的待办；AI 对话一句话可提炼多个日程/待办（actions 数组）。
- v4.6：AI 创建的待办/日程标记来源（TodoEntity/CalendarEventEntity 新增 source 字段，数据库 v4→v5 迁移），待办清单与日历卡片下方显示灰色小字「由 magic ai 创建」。
- v4.7：修复 AI 创建日程/待办失效：①去掉 jsonMode（response_format）——部分兼容 API 不支持直接 400，导致指令解析静默失败降级为普通聊天（AI 只口头答应不真创建）；②指令 JSON 解析容错增强（围栏/多余文字时正则提取 actions 片段）；③action 名称模糊归一化（create_event/add_event/创建日程 等变体都识别）；④普通聊天兜底：创建失败时 AI 必须如实告知，不再假装已记录；⑤指令关键词扩充（记/记录/记上/帮我）。
- v4.8：修复 AI 把日程误建到待办。①buildCommandParsePrompt 新增「待办 vs 日程」最高优先级判别规则：说日程/日历/会议/预约/几点或给具体时间点 → create_event；说待办/任务/事项或无具体时间 → create_todo；②executeCommand 用户话术兜底：create_todo 但带 startTime 或用户话含日程/日历/会议/预约/几点 → 纠正为 create_event；create_event 但用户话含待办/任务/事项 → 纠正为 create_todo。
- v4.9：①修复每日汇总不按预设时间发送：无精确闹钟权限时原降级 setWindow（Doze/国产ROM 省电下严重延迟甚至不触发）→ 改用 setAlarmClock（无需权限、精确触发、Doze 也唤醒）；②设置页新增通知权限（Android13+）与精确闹钟权限（Android12+）引导按钮，未授权时红字提示一键跳转；③AI 日程误判再加固：create_todo 但带了 dueTime 且用户没说待办/任务/记得/别忘了 → 纠正为 create_event；提示词新增「某天/某时段做某事即使没说日程二字也建日程」规则（如明天去立仁学习一天）；④create_event 无 startTime 时用 dueTime 兜底，避免建在当前时刻。
- v5.0：功能模块开关联动 AI。①已关闭功能的数据不再注入 AI 上下文（待办关则不注入 todos，日历关则不注入 events，日记关则不注入 diaries）；②system prompt 新增 module 状态标记（<module todo=on/off calendar=on/off diary=on/off />）+ 规则：用户要求使用已关闭功能时明确回复「该功能已关闭，请到设置中开启」，不执行不假装；③指令解析提示词同步标注开关状态，规则 9：已关闭功能的 actions 一律不输出；④指令执行前硬性过滤：关闭功能的 create/edit/delete 直接剔除，走普通聊天由 AI 提示已关闭。
- v5.1：AI 记住自己完成过的操作。功能关闭时不再注入详情，但注入 hidden 摘要（数量/最新一条/几条由 magic ai 创建），AI 询问历史时如实告知存在并提示去设置开启，绝不声称从未记录过（仅当 status=disabled 无历史数据时才说没记录过）；指令解析仍只给开启功能的数据（关闭功能不参与增删改）。
- v5.2：新增「日记自动回复」（设置可开关）：每次写完日记，Magic AI 以当前人格口吻自动回复（共情/建议/鼓励），回复存入 Magic AI 聊天记录并推送通知；未配置 API Key 时静默跳过。
- v5.3：待办页新增「每日打卡」tab：可创建打卡习惯（名称/每日提醒时间/目标天数可不设），每天一键打卡，打卡成功弹鼓励文案（按连续天数递进），卡片显示连续/累计天数与目标进度，点击卡片查看已打卡日期；提醒用每日一次性闹钟（触发后自动重排明天），纯净模式下全部取消。
- v5.4：每日打卡与倒数日上首页。①待办页新增第 4 个 tab「倒数日」：创建倒数日（名称/目标日期，编辑/删除），卡片显示剩余天数（今天/已过）；②首页（今日）有打卡习惯时显示「每日打卡」区块（可直接打卡，连续天数，打卡成功弹鼓励），有倒数日时显示「倒数日」区块（剩余天数）。
- v5.5：Magic AI 自动识别创建倒数日与每日打卡（说「每天背20个单词」「距离高考还有78天」即自动创建）；倒数日在日历上用红色标记（月视图红色圆点，选中当天红色卡片展示剩余天数）。
- v5.6：日记自动回复优化：仅新增日记时触发，编辑已保存过的日记不再自动回复。
- v5.7：新增日程提醒：新建/编辑日程可选提前提醒（10分钟~1天），到点通知提醒；删除/修改日程自动取消或重排提醒。
- v5.8：日历页新增「AI 规划」：输入需求/现有资源/截止时间/优先级，AI 自动分析拆解为可执行方案，可采纳（自动登记日程+提醒+待办）或重新生成。
- v5.9：AI规划增强（时长估算倒推/强制休息缓冲/精力曲线安排/优先级驱动排序）；日历页适配状态栏高度；每日待办完成后次日自动删除（0点清理+启动/开机/设置恢复兜底）。
- v6.0：AI规划超时修复（AiClient 支持 per-call 超时、规划放宽到 120s、prompt 精简注入上限 20 待办/30 日程）；性能内存优化（通知渠道只初始化一次、广播协程懒加载复用、批量采纳本地冲突检测、聊天 60s 超时兜底、Gradle 并行+缓存+配置缓存加速构建）。
- v6.2：AI 稳定性修复 + 日历交互升级。①AiClient 支持 jsonMode 自动降级：不支持的端点去掉 response_format 自动重试一次；AI 规划开启 jsonMode 提高 JSON 输出成功率；一句话建待办 60s 超时兜底降级纯文本。②日历上滑日程列表自动折叠为单周视图，头部按钮可展开/收起。③新增日程专注模式：日程卡点 ⏱ 进入全屏横屏大字时钟（秒级刷新）+ 日程标题 + 距开始/进行中倒计时，左下角截止时间小字、右下角退出按钮，进入屏幕常亮、退出恢复竖屏。
- v6.4：设置新增「模型支持图片识别」开关（设置 → 日记自动回复 → 模型支持图片识别）。开启后 AI 回复日记时，把日记附带的图片压缩（长边1024/JPEG80）成 base64 data URL，以 OpenAI vision 多模态格式（content 数组：text + image_url）连同文字一起注入识别；关闭则纯文本。需模型支持视觉（如 gpt-4o / qwen-vl）。AiClient.ChatMessage 自定义序列化器：无图时 content 输出普通字符串（完全向后兼容），有图时输出 vision 数组格式。
- v6.3：性能评估优化（auditing-compose-performance skill）。①启用 R8 全量优化 + 资源压缩（isMinifyEnabled/isShrinkResources=true，proguard 补 kotlinx.serialization/Room keep 规则）——启动 AOT 加速、APK 大幅瘦身；②Flow 收集全部升级 collectAsStateWithLifecycle（生命周期感知，后台不重组）；③AiChat 列表补 key、Diary 分组/Home 过滤加 remember 缓存；④开启 Compose Compiler reports（build/compose_compiler_reports/ 稳定性诊断）。

## 构建

```bash
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK（debug keystore 签名）
```

产物位于 app/build/outputs/apk/。交付包统一命名 MagicNote-<版本号>.apk。

## 环境备注

- SDK 位于 /root/Android；ARM64 环境已内置 aapt2 替换（setup_android_env.sh）。
- proot 环境 release 打包需合并 linked-resources-binary-format ap_ 中间产物再签名（见交付记忆）。
