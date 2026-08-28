# Compose Performance Audit — 2026-08-28 — MagicNote (v6.3)

## Environment
- Compose UI: BOM 2026.01.01 (Material3)
- Compose Compiler: Kotlin 2.3.10 内置（Strong Skipping 默认开启）
- Kotlin: 2.3.10 / AGP: 9.0.0 / Room: 2.7.1
- R8: AGP 9.0 默认 full mode（`proguard-android-optimize.txt`）
- Device: 无真机（构建环境限制）→ 测量改用「release+R8 构建 + 静态诊断 + Compose Compiler reports」

## Baseline (Phase 1)
> ⚠️ 环境限制说明：当前为 proot 构建环境，无法连接物理设备运行 Macrobenchmark，
> 基线改用可验证的替代指标：APK 体积、Compose Compiler 稳定性报告、静态代码审计。
- APK 大小（release 合并签名后）：**13.8 MB**
- R8：**未启用**（isMinifyEnabled=false）
- Flow 收集：`collectAsState`（非生命周期感知）7 文件 / 15+ 处
- Compose Compiler reports：未生成
- 不稳定 UI 参数类：2 个（DiaryEntity / HabitEntity）
- Baseline Profile：无

## Diagnosis (Phase 2)
- **Top-1：R8 未启用** —— Compose 无法做 lambda 分组优化、无 dead code 消除、启动无 AOT 加速、APK 无压缩
- **Top-2：Flow 收集非生命周期感知** —— 后台时所有页面持续重组（HomeScreen 同时收集 6 个 Flow、TodoScreen 4 个、SettingsScreen 5 个）
- **Top-3：unstable UI 参数类** —— DiaryEntity / HabitEntity 含 `List<String>`（接口类型不稳定），阻止日记/打卡列表行 composable 跳过重组
- **Top-4：列表/计算热点** —— AiChat `items(messages)` 无 key（新消息插入全量重组）；Diary `groupBy` 与 Home 三个 filter 无 remember（每次重组重算）
- 说明：Room 生成的 `*Dao_Impl`、AiClient serializer 类被判 unstable，均为非 UI 参数的基础设施，不影响重组跳过

## Fixes applied (Phase 3)
| Skill | Change | Files | 可验证收益 |
| ----- | ------ | ----- | ---------- |
| build/configuring-r8-for-compose | 启用 R8 full mode + 资源压缩；proguard 补 kotlinx.serialization / Room keep 规则 | app/build.gradle.kts, proguard-rules.pro | APK 13.8MB → 2.4MB（**-82%**），启动 AOT 加速 |
| side-effects/collecting-flows-safely | `collectAsState` → `collectAsStateWithLifecycle`（+lifecycle-runtime-compose 依赖） | 7 个 Screen/Nav 文件 | 后台停止收集与重组，省电省内存 |
| lists/optimizing-lazy-layouts | AiChat 列表补 `key = { it.timestamp }` | AiChatScreen.kt | 新消息只新增行，不复用全部气泡 |
| recomposition/choosing-derivedstateof | Diary `groupBy`、Home 三个 filter 加 `remember` 缓存 | DiaryScreen.kt, HomeScreen.kt | 重组不再重复 O(n) 遍历 |
| stability/stabilizing-compose-types | `@Immutable` 标注 DiaryEntity / HabitEntity（全 val 安全） | DiaryEntity.kt, HabitEntity.kt | 6/6 实体类全部 stable，列表行可跳过 |
| measurement/diagnosing-compose-stability | 开启 `composeCompiler { reportsDestination }` | app/build.gradle.kts | 持续产出 classes.txt/composables.txt 诊断依据 |

## Verification (Phase 4)
- APK 体积：13.8 MB → **2.4 MB**（Δ -11.4 MB / -82%）✅
- 实体稳定性：4/6 → **6/6 stable** ✅（DiaryEntity、HabitEntity 由 unstable → stable）
- Flow 收集：15+ 处全部 lifecycle-aware ✅
- 构建：release + R8 full mode 构建通过（16m23s 首次 / 9m44s 增量）✅
- Baseline Profile 重新生成：否（无 Macrobenchmark 模块，见 Open items）
- CI stability gate：未配置（见 Open items）

## Open items / follow-ups
- [ ] **Baseline Profile**：接入 Macrobenchmark 模块（AGP 8.2+ 模板），真机生成 `baseline-prof.txt`，预计启动再快 30%+
- [ ] **CI stability gate**：`compose-stability-analyzer` 插件 `stabilityDump` 基线 + `stabilityCheck` 门禁
- [ ] **StrictMode**：debug 构建开启主线程违规检测（ThreadPolicy + VmPolicy）
- [ ] **真机验证**：Macrobenchmark 冷启动 StartupTimingMetric + 滚动 FrameTimingMetric（P50/P90/P99）
- [ ] **Compose HotSwan**：开发热重载，编辑-截图-迭代闭环提速
