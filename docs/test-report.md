# 同晓 · 测试报告

> 生成方式：本文件由用户在模拟器 `Pixel_7_API_36` 上实跑的 `gradlew connectedDebugAndroidTest` 结果与各里程碑关卡记录汇总。
> 状态：**已完成**（最终全量运行：374 tests / 0 failed / 0 skipped，模拟器 Pixel_7_API_36）

## 1. 结论速览

| 项目 | 结果 |
|---|---|
| 单元/集成测试（instrumentation） | **374 tests / 0 failed / 0 errors / 0 skipped** |
| 构建（G1，含 lint） | `BUILD SUCCESSFUL`，lint **0 error**，仅余 2 条已知提示 |
| 手动验证（G5） | 保存落库、滚轮时间选择、响铃全链路、Provider 只读、双机互助（见 §4） |

## 2. 测试策略

### 2.1 分层

| 层 | 测试方式 | 代表用例 |
|---|---|---|
| 纯逻辑（算法/协议/统计） | 确定性种子 + 合成数据 + 边界值 | `NextTriggerCalculatorTest`、`HolidayResolverTest`、`MessageCodecTest`、`AdaptiveEngineTest`、`StatsAggregatorTest` |
| 数据层 | 内存 Room（`inMemoryDatabaseBuilder`） | `TongxiaoDbTest`、`AlarmRepositoryTest`、`SettingsStoreTest` |
| 系统集成 | 真机/模拟器 + 系统状态断言 | `AlarmSchedulerTest`（`getNextAlarmClock`）、`RingServiceTest`（`getActiveNotifications`）、`SystemEventReceiverTest`、`SelfCheckWorkerTest`（`FLAG_NO_CREATE` 探测） |
| UI | ActivityScenario + 视图状态断言 | `AlarmListScreenTest`、`EditAlarmActivityTest`、`SettingsTabTest`、`UnlockActivityTest`、`StatsScreenTest` |
| 跨进程/网络 | 127.0.0.1 回环双端 | `PeerLinkTest`、`GroupCoordinatorTest` |

### 2.2 "假绿"防范

- 每个判分点都断言**系统真实状态**，而不是"方法被调用过"：闹钟是否真的被 `AlarmManager` 记住、通知是否真的存在、`PendingIntent` 是否真的能取回
- 反例测试：越权指令必须返回拒绝码而非静默执行；坏 JSON 必须降级为"不提前"而不是抛异常；粘包必须解析出两条消息
- 评审闭环：每个里程碑用**无上下文评审代理**只给 diff + 设计文档相关章节复审，抓出过 4 个真实缺陷（见 §5）

## 3. 用例清单与结果

> 数字待最终全量运行回填。

| 测试类 | 用例数 | 覆盖点 |
|---|---|---|
| `TongxiaoDbTest` | 4 | 6 表建库、DAO 基本读写、外键清理 |
| `SettingsStoreTest` | 4 | 总闸/城市/默认挑战配置的读写与默认值 |
| `AlarmRepositoryTest` | 5 | 级联删除、非法 id 拒绝 |
| `HolidayResolverTest` | 9 | 2026 公告 7 假日段 + 6 补班日、缺失年份降级 |
| `NextTriggerCalculatorTest` | 8 | 补班优先、跨周、ONCE 过期、DST 重叠 |
| `AlarmSchedulerTest` | 6 | 注册幂等、取消、重排、精确权限降级 |
| `RingActionsTest` | 7 | 停铃重排、ONCE 自关、贪睡钳制、跳过 |
| `RingServiceTest` | 5 | 前台服务、通知、停止清理 |
| `StopRingActivityTest` | 5 | 停铃写库、返回键停铃、null 行 |
| `SystemEventReceiverTest` | 4 | 开机/改时/时区/更新重排 |
| `AlarmListScreenTest` | 3 | 列表渲染、空态、开关 |
| `EditAlarmActivityTest` | 4 | 新建保存、编辑更新、删除级联、排程注册 |
| `SettingsTabTest` | 5 | tab 切换、城市显式保存、快捷城市、默认 tab |
| `ChallengeEngineTest` | 13 | 题库、算术生成、会话、配置钳制 |
| `UnlockActivityTest` | 3 | 答对停铃、紧急停止、DIRECT 直停 |
| `AdaptiveEngineTest` | 21 | 中位数、样本门槛、跨零点、阈值 |
| `WeatherClientTest` | 25 | 缓存过期、坏 JSON、恶劣判定、提前钳制 |
| `StatsAggregatorTest` | 8 | 起床趋势、贪睡榜、被静音榜、空数据 |
| `SelfCheckWorkerTest` | 5 | 缺注册补排、周期任务注册 |
| `AlarmProviderTest` | 6 | 只读查询、写拒绝、未知路径 |
| `PeerStatsStoreTest`(15) `GroupSessionTest`(4) | 19+ | 离线榜单快照：乱序旧包不覆盖、24h stale 仍显示、身份以链路为准、退组清理 |
| `RegionCatalogTest`(25) `RegionPickerDialogTest`(5) `RegionWheelControllerTest`(7) | 37 | 滚轮级联与安全性（含"省列表变短导致越界"崩溃回归）、 三级行政区目录（34/365/3094，含港澳台）、坏 JSON 降级、直辖市/特区折叠、无区县市隐藏第三轮、滚轮回填（两段路径） |
| `MessageCodecTest`(11) `GroupProtocolTest`(7) `RequestPayloadsTest`(7) `PeerLinkTest`(6) `GroupManagerTest`(6) `GroupCoordinatorTest`(28) `GroupCleanupTest`(4) `NsdDiscoveryTest`(4) `GroupIdentityTest`(3) `GroupTabTest`(2) | 78 | 半包/粘包/超长/未知类型、HELLO 握手、越权拒绝、待确认请求生命周期、退组清理、宿舍页 tab 接线 |

## 4. 手动验证（G5）记录

| 场景 | 操作 | 观察结果 |
|---|---|---|
| 保存闹钟 | 新建 → 底部保存 | 回列表并出现条目，Toast 显示"下次响铃：…" |
| 时间选择 | 点大时间 | 双滚轮（`TimePicker` + 2 个 `NumberPicker`），确定后回填 |
| 边缘适配 | 进入各页 | 工具栏 top=136px（=状态栏高度），底部导航避开手势条 |
| 响铃链路 | 设 1 分钟后的一次性闹钟 → 锁屏 | 到点拉起全屏停铃页（logcat: `Displayed ...StopRingActivity`）；该自测按钮已在验收前移除 |
| Provider | `adb shell content query --uri content://com.course.tongxiao.alarms/alarms` | 游标可用；`content insert` → UnsupportedOperationException |

## 5. 评审抓出的真实缺陷（可作为答辩素材）

1. **镜像表主键用自增 id** → 对端重装后会静音错闹钟：改为 `srcAlarmUid + uid` 唯一索引，找不到返回 `ALARM_NOT_FOUND`
2. **`deleteWithEvents` 接受 id=0** → 静默删掉 `alarmId=0` 的事件桶：前置抛 `IllegalArgumentException`
3. **停铃页返回键/空行导致铃声无法停止**（BLOCKER）→ 通知加"停止" action、返回键等价停铃、预测式返回处理
4. **`DAILY` 曾被 `skipHolidays` 静默跳过 33 个假日日** → 口径改为 DAILY 恒响，并写回设计文档
5. **`startForegroundService` 5 秒契约未满足** → `RemoteServiceException` 杀进程：改为先 `startForeground` 再校验
6. **Provider `Uri.getPath()` 带前导斜杠** → 拒绝自己的 URI：改用 `getPathSegments()`
7. **`StatsFragment` 只在 onResume 加载** → 宿主 show/hide 时永远空白：改为 onViewCreated 先加载 + onHiddenChanged 刷新


## 5.1 G4 独立评审（收尾轮）抓出并修复的问题

评审代理（无上下文、只读代码）在本轮抓出 2 个 BLOCKER + 7 个 MAJOR，全部已修并补测试：

| 级别 | 问题 | 后果 | 修复 |
|---|---|---|---|
| BLOCKER | `CMD_MUTE` 的 `ownerDev` 校验方向反了（要求 `sender == ownerDev`） | 真实的"舍友静音我的闹钟"100% 被拒，远程静音主线不可能成功 | 改为 `identity.deviceId().equals(ownerDev)`（目标必须是本机闹钟），发起方资格仍由权限位+心跳决定；补"跨设备合法静音必须 ACK ok"测试 |
| BLOCKER | 宿舍页无静音入口、`broadcastSync()` 零调用 | 两台机器演示不出"B 点静音 → A 停声" | 成员行渲染共享时刻表 + "正在响"标识 + 静音按钮；握手成功/内容变化（5s 轮询指纹）/视图创建三处触发推送 |
| MAJOR | 天气提前量在真机永不生效（`advanceEnabled` 从无写入点） | 功能是死代码，编辑页却宣称会提前 | 编辑页新增"天气不佳时提前响"开关并写入 `advanceEnabled/advanceMaxMin`；补 scheduler 接线测试（断言注册时刻早 10 分钟） |
| MAJOR | 统计页在主线程读 SharedPreferences | 首屏 StrictMode 磁盘读 / ANR 风险 | 移到 worker，UI 只做渲染 |
| MAJOR | 榜单窗口不一致（中位数按 7 天、计数按 14 天） | 榜单数字与文案不符 | `summarizeForPeers` 统一按窗口过滤；补"窗口外 SNOOZE 不计入"测试 |
| MAJOR | 地名在线解析按叶子名取首条 → 同名区县解析到别省 | 用错城市天气决定是否提前 | 校验 country=CN 且**必须匹配省/市级段**（叶子名不足以区分"朝阳区/鼓楼区"）；缓存键改用完整路径；失败负缓存；补 5 条测试 |
| MAJOR | 统计页广播只在打开时发一次 | "最近同步"只增不减，重连不自愈 | `onResume` 起 5 分钟 ticker，`onPause` 停 |
| MAJOR | 跨零点提前会让接收端按前一天判星期 | 提前被吞并写假 `SKIPPED_HOLIDAY` | `advancedTriggerAt` 增加"提前后不得跨日"守卫 + 测试 |
| MAJOR | `SettingsFragment` 后台线程 `requireContext()/getString()` + executor 不可复用 | 旋转/退出时可能崩溃 | 进 worker 前抓 appContext 与文案；executor 按需重建 |
| MINOR | 远程静音与用户停铃重复记两条 STOPPED | 污染榜单向导与自适应样本 | `RingActions.onStopped` 5 秒去重窗口 + 测试 |
| MINOR | 响铃瞬间 `currentRingingUid` 仍为 null | 该窗口内的静音被回 NOT_RINGING | `RingService` 增加同步可知的 `currentRingingAlarmId`，网关兜底匹配 |
| MINOR | 区县轮自绑定监听、榜单文案写"平均"（实为中位数）、保存可双击、旧城市值静默落成北京 | 各种小偏差/重复插入 | 逐项修掉 |

## 5.2 第二轮 G4 评审（用户视角 / 健壮性）抓出并修复的问题

| 级别 | 问题 | 后果 | 修复 |
|---|---|---|---|
| P0 | `Alarm.sharedToGroup` 全仓无写入点（只被读） | 镜像恒空 → 宿舍页永远不显示"正在响/静音"，互助演示只剩"看到昵称" | 字段默认改 true + 建组/入组时 `AlarmDao.setSharedToAll(true)` 回填历史行 + 编辑页新增「共享给宿舍组」开关 |
| P0 | 代设/授权在 UI 上不存在（`setGrantedMask`、`setRequest` 零调用） | 文档让验收人去点不存在的按钮 | 权限 chips 可点（逐项授权）+ 成员行「申请代设」+ 入站 `NOTIFY_OP`/`SET_RESPONSE` 操作流 + 失败原因文案 |
| P0 | 挑战开启后点"停止/稍后"必崩：`postDelayed` 未取消 + executor 已 shutdown | `RejectedExecutionException` 弹"屡次停止运行"，正好命中演示 B 段 | Runnable 字段化，`onPause`/`onDestroy` 取消回调，`stopAndFinish` 前置 `isFinishing()` 早退 |
| P1 | 通知栏"停止"只静音不记账 | 统计丢样本；ONCE 闹钟留"开着但永不响"的僵尸行 | `RingService` 停止分支在 worker 上补 `RingActions.onStopped`（与停铃页同语义） |
| P1 | 编辑页异步读库未完成时点保存 → 插入一条 07:30 的重复闹钟 | 用户看到"列表里多了个闹钟" | 加 `loaded` 守卫，读取完成前保存只提示"稍等" |
| P1 | 删除无二次确认（且级联清历史） | 配置好的闹钟被一键永久删除 | 二次确认对话框（含闹钟名与"不可恢复"） |
| P1 | 挑战页无滚动容器，大字号/小屏会顶掉"紧急停止" | 唯一逃生口不可见 → 违反"不能困人" | 题目区改 ScrollView，紧急停止固定在底部（不参与滚动） |
| P1 | 底部导航 4 个 tab 同色（无选中态） | 看不出当前在哪个 tab | 新增 `color/nav_item_tint.xml` 状态选择器 |
| P1 | 小字 `muted` 对比度 4.2:1（低于 WCAG AA） | 11–13sp 的"下次：…"等看不清 | `#6F7D6E` → `#5A6759`（≈5.4:1） |
| P1 | 权限被拒后静默降级，无任何引导 | 用户不知道"锁屏不亮屏/晚几分钟"是权限问题 | 列表页权限横幅（通知/精确闹钟两种，带跳转），决策逻辑抽 `PermissionBanner` 并单测 |
| P1 | 天气"已关联"不反映真实结果 | 演示时"提前响"可能静默空转 | 保持"失败不提前"语义；设置页改为可选真实状态（v1 记录在案） |
| P2 | Provider projection/selection 原样拼 SQL（exported） | 可用子查询读到 `group_members` 等其它表 | 列白名单 + selection/sortOrder 形态校验，越界抛 `IllegalArgumentException` |
| P2 | 兜底通知硬编码频道 id，频道未创建时静默丢失 | 极端情况下完全无通知 | `RingService.ensureChannel()` 静态化并在兜底前调用 |
| P2 | 无最大响铃时长 | 停铃页不可达时会响到没电 | 10 分钟上限，超时记账 `source=timeout` 后自停 |
| P2 | 挑战 ADD_SNOOZE 只改文案、反馈不清理、README 写"可选 1–5 题" | 策略名不副实 / 文案与实际不符 | 真实冷却（禁用选项 N 秒后换题）、每题清空反馈、文档改成"引擎支持 1–5 题，v1 界面固定 2 题" |
| P2 | 列表底部被 FAB 压住、地区按钮在加载中报"数据不可用"、榜单空态双文案、Switch 无 contentDescription、"长按 3 秒"文案与实际长按不符 | 各种打磨瑕疵 | 逐项修掉（含文档 11 处口径对齐） |

## 5.3 专项 QA：模拟器实测 + 自修（12 项流程）

由一个专职 QA 在 `Pixel_7_API_36` 上按 12 项清单实测（首启/闹钟全流程/响铃/挑战/地区/宿舍/统计/权限/生命周期/Provider/异常输入），抓出 **7 个真缺陷**并全部修复，另发现 4 个环境限制与 2 个记录项。

| 编号 | 现象 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| **D4（P1 漏响）** | 点"稍后提醒"后，10 分钟后的唤醒被取消；仅一次的闹钟还被禁用 | 贪睡用 `RingService.stop()`（ACTION_STOP_RING）静音，而该动作会记账并重排/禁用，正好覆盖掉刚排的 snooze（同一 requestCode） | 新增 `EXTRA_SILENT_ONLY` + `RingService.silence()`：只静音、不记账，贪睡自己负责 `onSnoozed` | 修后 pending=+9m56s、enabled=1，**实测 10 分钟后真的再次响铃**；补单测 |
| D1 | 输入闹钟名时键盘遮住"保存/删除" | IME inset 加在 ScrollView 上，固定底栏仍被键盘覆盖（edge-to-edge 下 adjustResize 不生效） | 改为给根布局加 `padSystemBarsAndIme` | 截图：键盘展开时底栏完整可见 |
| D3 | 关闭闹钟后仍显示"下次：今天 08:30" | ViewModel 原地改 `alarm.enabled`（adapter 持有的同一个对象）→ DiffUtil 判定未变 → 从不重绑 | 删除原地赋值，交给 Room 新对象触发重绑 | `enabled=0` 且"下次"行清空 |
| D5 | 答错题没有任何反馈 | 先写反馈再 `showQuestion()`，被后者清空 | 先渲染题目再写反馈 | 显示"答错了，再想想"，可重答 |
| D6 | 关闭互助总闸后回宿舍页无提示（要重启） | tab 用 show/hide，不走 `onResume` | 新增 `onHiddenChanged` 刷新 | 切 tab 后横幅立即出现 |
| D7 | 未命名但存在的闹钟在统计里显示"已删除的闹钟" | 只判 `label` 为空，未区分"行已删/行在但无名" | 用 `labels.containsKey` 区分，无名显示默认名 | 显示"闹钟 · 贪睡 1 次" |
| D2 | 贪睡滑块没有任何数值 | Material3 Slider 默认不画 label | 标题行右侧加读数并随拖动刷新 | 拖动显示"10 分钟 → 20 分钟" |

QA 之后 Lead 又补了两处：

- **D8（决策项）**：重新入组会 `setSharedToAll(true)`，静默覆盖用户手动关闭的单闹钟共享 → 改为**仅首次入组做一次批量共享**（`GroupIdentity.hasBackfilledSharing/markSharingBackfilled`），之后尊重用户选择；补回归测试。
- **列表 DiffUtil 漏字段**：`areContentsTheSame` 未比较 `dayMask`/`advanceEnabled`/`advanceMaxMin`，而这些会影响"下次：…"显示 → 补上。

环境限制跳过（非缺陷）：NSD 注册失败（模拟器无法构造）、ADD_SNOOZE 冷却（App 无该策略入口）、设备时钟跳变、滚轮只到分钟粒度（无法构造"10 秒后"）。

## 5.4 专项界面测试（逐屏 + 四环境）

由 UI QA 在模拟器上逐屏截图（82 张，`D:\Android\qa-ui\`）并在 **深色 / 1.3× 与 2.0× 字号 / 横屏 / 720×1280 小屏** 四种环境下复测，抓出 **12 个界面缺陷**并修复。

| 编号 | 屏 | 现象 | 根因 | 修复 |
|---|---|---|---|---|
| **D1（P0）** | 全部 tab | **两个 Fragment 同时挂载**：列表出现两套卡片、文字互相压字；快速切 tab 也会串屏 | `MainActivity.swap()` 用异步 `commit()`，`onCreate` 里 `setSelectedItemId` 与显式 `showAlarms()` 各 add 一次（第一次事务还没执行，`findFragmentByTag` 返回 null） | 改 `commitNow()`，swap 变为同步幂等；补回归测试（`alarm_list` 实例数 2→1、连切 3 tab 不叠加） |
| D2–D6 | 全局 | Material 默认紫残留：底部导航选中胶囊/滑块轨道淡紫、弹窗底色淡紫、关闭态开关拇指与轨道同色"像禁用"、输入框描边对比度仅 1.3:1 | 未覆盖 `secondaryContainer` / `surfaceContainer*` / `outline` 等角色 | 补绿系角色（`values` 与 `values-v27` 同步），描边改 `#6E7D6B`（≥3:1） |
| D7 | 设置/统计/宿舍 | 标题比其它页右移 24dp，且**随内容滚动消失** | 三个布局把 `MaterialToolbar` 塞进带内边距的 ScrollView | 工具栏移出滚动区，固定 app bar（与闹钟页一致） |
| D8 | 统计折线 | 2.0 字号下 Y 轴 "06:00" 被裁成 ":00"、X 轴日期被裁一半 | `SleepTrendView` 用固定 dp 留白而标签字号是 sp | 按 `measureText`/`fontMetrics` 动态算留白 |
| D9 | 设置 | 2.0 字号下"选择地区（省 / 市 / 区县）"被硬截断 | 单行固定 56dp 按钮 | `autoSizeTextType` 10–14sp |
| D10 | 宿舍 | "操作记录"标题下全空白（舍友/待确认都有空态） | `renderOpLog()` 空时直接 GONE，无替代视图 | 补 `group_op_log_empty` 空态 |
| D11 | 停铃页 | "稍后提醒"是平台灰方块，像禁用且与主题割裂 | 用 `android.widget.Button` | 改 MaterialButton（停止 filled / 稍后 outline） |
| D12 | 设置 | 未选地区时仍显示"清除"（点了没反应） | `renderRegion()` 未控制可见性 | 无地区时 GONE |

Lead 随后按报告拍板的两项设计决策 + 三项打磨：

- **锁竖屏**（四 Activity `screenOrientation="portrait"`）：横屏带横幅时列表不足一行；Android 16+ 大屏会忽略该请求，故列表在横屏额外隐藏副标题兜底。lint 两条相���规则在 `app/lint.xml` 里有理由地豁免。
- **摇一摇入口**：编辑页改为**三选一按钮组**（关闭/选择题/摇一摇），SHAKE 此前 UI 不可达；补 2 条测试（持久化为 SHAKE、挑战页渲染摇动计数）。
- 设置页 Snackbar → Toast（无 CoordinatorLayout 时 Snackbar 会盖住底部导航约 2 秒并吃点击）。
- 题库半角问号 → 全角（2 处）。
- 列表 DiffUtil 补 `dayMask`/`advanceEnabled`/`advanceMaxMin`（会影响"下次：…"显示）。

记录但不修：2.0 字号下地区三滚轮相邻列文字重叠（NumberPicker 无 ellipsize/textSize API，需改选择流程）；横屏+2.0 挑战页只露 2 个选项（可滚动、紧急停止仍在屏内）；深色模式下保持浅色主题（v1 有意锁定）。

## 5.5 全量回归（Lead 执行）

一次全量回归重新跑通了"构建 + 全量测试 + 设备端功能流"，并**抓到一个此前没被任何测试覆盖的真回归**：

| 项 | 结果 |
|---|---|
| `gradlew build`（含 release 变体与 lint） | BUILD SUCCESSFUL；lint **0 error / 0 warning** |
| `connectedDebugAndroidTest`（先 `pm clear`） | **374 tests / 0 failed / 0 errors / 0 skipped** |
| 冷启动 / 四 tab | 正常（闹钟 / 互助 / 统计 / 设置），无重复 Fragment |
| 新建闹钟 → 保存 → 系统注册 | `dumpsys alarm` 显示 `RTC_WAKEUP` + `exactAllowReason=policy_permission` + `showIntent=startActivity`（全屏页链路已排程） |
| 关闭闹钟 | "下次"行消失，`dumpsys alarm` 活动注册 **2 → 0** |
| **重新开启闹钟** | **回归缺陷**：DB 已 `enabled=1` 但 AlarmManager 里没有注册（重开等于没开） |
| 编辑页 / 删除 | 删除按钮仅在编辑态出现；确认框出现；确认后行删除、注册 2 → 0、Provider 查询返回空 |
| 地区选择器 | 打开显示 3 个滚轮，取消无崩溃 |
| 统计页 | 趋势/贪睡/被组员静音/互助榜单文案正确 |
| 互助 tab | 创建组出 6 位组码、默认昵称"组员xxxx"、"共享中：我的 1 个闹钟"；退出组回到加入卡 |
| 权限引导 | 拒绝通知权限后，列表页出现"通知权限未开启：锁屏时可能看不到停铃页，会只响铃不亮屏" + "去开启" |
| Provider | `content query` 正常返回；`--projection "label,(SELECT nick FROM group_members)"` 被 `IllegalArgumentException` 拒绝 |
| 崩溃 | 全程 `logcat -b crash` 为空 |

**发现的回归（已修 + 已补测试）**：`AlarmListViewModel.setEnabled()` 在"关闭闹钟后行不重绑"那次修复里不再原地修改 `alarm.enabled`，但它随后把 adapter 持有的**旧对象**交给 `AlarmScheduler.schedule()`；调度器看到 `enabled=false` 直接 `cancel`，于是"重新打开开关"只是把数据库置了 1，**AlarmManager 里始终没有注册**——UI 显示"下次：…"却永远不会响。
修法：`setEnabled` 在写库后重新从 Room 读一行（新增 `AlarmRepository.getById`）再交给调度器；补测试 `enablingAnAlarm_registersItAgain`（断言 DB `enabled` 与 `PendingIntent.FLAG_NO_CREATE` 都成立）。

> 这条正是全量回归的价值：单元测试各自绿、UI 也"看起来对"，只有把"关→开→查系统注册"连起来走一遍才会暴露。

## 5.6 自定义题目（补齐漏做能力）

用户指出"自定义题目还没实现"——确认 `design.md`/代码里此前都**没有**这项，属遗漏，本轮补齐。

| 层 | 实现 |
|---|---|
| 存储 | `CustomQuestionStore`：SharedPreferences 存 JSON 数组，最多 **50 题**；题干 ≤120 字、4 个选项各 ≤24 字、正确项 0..3；**写入严格校验、读取宽松跳过**（坏行只丢该行，不影响停铃） |
| 引擎 | `ChallengeEngine(bank, custom)` + `ChallengeConfig.questionSource`：**按闹钟选"内置题库 / 我的题目"**；选"我的题目"时只用自出的题，若一道都没出过则自动回落到内置题库（不能出现答不出的挑战）；BUILT_IN 时完全不用自出题 |
| 界面 | `CustomQuestionActivity`：列表（序号 + 题干 + 正确答案 + 删除）、新增对话框（题干 + 4 选项 + A/B/C/D 单选）、删除二次确认、空态引导、满 50 题提示；入口在**设置 → 自定义题目（停铃挑战）**，摘要显示"N 道题 / 还没有自定义题目" |
| 测试 | `CustomQuestionStoreTest`(13)：往返、空题干/空选项/越界答案/选项数不对/超长全部拒绝、最多 50、按索引删除、坏 JSON 与坏行降级、清空；`ChallengeEngineTest`(+2)：自定义题在类型过滤下仍优先出现、不足时与内置题库混用；`CustomQuestionActivityTest`(5)：空态、保存后渲染、非法输入拒绝、删除、对话框字段与默认答案 |

设备实测：编辑页选「选择题」→ 出现「题目来源：内置题库 / 我的题目」（无自出题时"我的题目"禁用并提示去设置）；设置页进入 → 空态 → 新增（题干"2+2=?"、选项 3/4/5/6、正确 B）→ 列表出现"1. …/ 正确答案：B. 4" → 删除并二次确认 → 回到空态；`logcat -b crash` 为空。

## 5.7 答错惩罚（三种都真实生效）

此前"答错惩罚"虽有三种配置，但只有"重试"真的有效：另外两种只改了一句提示文案。本轮补齐并做成**按闹钟可选**：

| 惩罚 | 实际行为 |
|---|---|
| 原地重试 | 同一题重新作答，无额外代价（默认） |
| 锁定冷却 | 4 个选项禁用 `max(5, 延迟+10)` 秒，倒计时结束后自动换下一题 |
| 加一道难题 | 立即追加一道**难度 3** 的算术题（最多 2 道，保证挑战终会结束） |

配套改动：进度行常显答错次数（"第 1 / 2 题 · 已答错 2 次"）；编辑页在「选择题」下新增三选一「答错之后」；策略随 `challengeOverrideJson` 存储与组内同步，接收端 `clamped()` 后生效。

测试：`UnlockActivityTest`（ESCALATE 追加难题后题数 2→3）、`EditAlarmActivityTest`（选"锁定冷却"持久化为 `ADD_SNOOZE`）；共 **366 tests / 0 failed**。

## 5.8 功能自检（Lead 全流程实测）

按"测试一下功能是否有异常"做了一轮功能自检，**发现并修掉 2 个真实可用性问题 + 1 个 flaky 测试**：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| A1 | 自定义题目对话框里，键盘展开时"正确答案"单选区被键盘盖住，无法边填边选（实测两次点偏） | 选择答案的单选区在选项字段下方，键盘一弹就挡住 | 把 4 个单选点**内嵌到每个选项字段左侧**（边填边标）；互斥逻辑抽成 `wireCorrectRadios()`，生产与测试共用同一段代码 |
| A2 | 键盘展开时对话框的"确定/取消"被遮住（实测一次点到了桌面壁纸选择器） | `AlertDialog` 默认不随 IME 调整 | 对话框 `setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE)`，实测键盘展开后"确定"仍可见（bottom=1270/2400） |
| A3 | `GroupManagerTest` 在全量套件里偶发失败（单独跑 3/3 全过） | 真实 socket 的"发送方链路"比 `handshake()` 晚一步注册；测试对首次 `send()` 直接 `assertTrue` | 改为 `TestAwait.until(...)` 重试到链路就绪；连跑两轮全量均 **366/366** |

实测确认正常的功能（节选）：

- 自定义题目：新增（题干 + 4 选项 + 点 B 标正确）→ 列表显示 `1. 7x7 / 正确答案：B. 49`；删除 + 二次确认 → 回到空态
- 每闹钟挑战配置：编辑页勾「选择题 + 我的题目 + 加一道难题」→ 保存 → Provider 读回 `challengeOverrideJson={"mode":"QUIZ",...,"wrongPolicy":"ESCALATE","questionSource":"MY_OWN"}`
- 四个 tab、编辑页三组选择器（模式/题目来源/答错惩罚）、地区三滚轮、Provider 只读与注入拒绝、权限横幅、删除流程、闹钟注册与取消——均正常，`logcat -b crash` 全程为空

> 一条给后来者的经验：`adb shell pm clear` 会连运行时权限一起清掉，之后要重新 `pm grant POST_NOTIFICATIONS`，否则会出现"权限横幅挡住点击导致脚本点偏"的假故障。

## 5.9 铃声可选（补齐漏做能力）

用户反馈"真实响铃可以，但没办法修改铃声"——确认 `Alarm.ringtoneUri` 与 `RingService` 早就支持自定义铃声，**但编辑页从未给入口**，所以永远是系统默认。本轮补齐：

| 项 | 实现 |
|---|---|
| 入口 | 编辑页「铃声」行（在振动开关下方，显示当前铃声名，默认显示"默认闹钟铃声"） |
| 选择器 | 系统 `ACTION_RINGTONE_PICKER`（`TYPE_ALARM`，自带试听、显示默认项、不提供静音以免出现"只震动"的误解） |
| 存储 | `alarm.ringtoneUri`；选"默认"或未选 → 存 `null`（跟随系统闹钟音，系统换音也会跟着变） |
| 播放 | `RingService`：自定义 URI → 系统闹钟音 → 通知音兜底（找不到文件也会响） |
| 健壮性 | 铃声已被删除时按钮降级为"无法读取这首歌的名字"，不崩 |
| 测试 | 新增 3 条：新建闹钟保持默认（`ringtoneUri=null`）、编辑已有保留自定义 URI、选择结果归一到 `null` 的映射规则 |

设备实测：编辑页点「铃声」→ 打开系统 Sound Picker（`Currently set / My Sounds / Pixel Sounds / Gems`，含 SAVE）→ 返回编辑页不崩溃、按钮仍显示"默认闹钟铃声"（未选择时不变更）。

> 环境备注：期间模拟器进程句柄残留（进程名是 `qemu-system-x86_64-headless`）导致 AVD 被占、全量测试卡在 16/369；清理锁文件并重启后 **369/369** 全绿。

## 5.10 振动强度（轻/中/强）

| 项 | 实现 |
|---|---|
| 数据 | `alarms.vibrateStrength`（0 轻 / 1 中 / 2 强，默认中）；**DB 版本 1 → 2，带正规迁移** `MIGRATION_1_2`（`ALTER TABLE alarms ADD COLUMN vibrateStrength INTEGER NOT NULL DEFAULT 1`），不清库 |
| 表达方式 | Android 无"强度"API，故用**振幅 + 节奏**：轻 `[0,200,800]`@60 / 中 `[0,350,400]`@140 / 强 `[0,500,150]`@255；设备无振幅控制时只靠节奏区分。映射集中在 `VibrationProfiles`（纯逻辑、可测） |
| UI | 编辑页「响铃时振动」下方三选一，**点一下立刻试振**（同一振幅）；关闭振动时自动隐藏 |
| 兼容 | 未知值一律回落"中" |

**真实升级验证**（本轮重点）：用旧提交 `ff3644e` 单独构建 v1 APK（DB version 1）→ 安装并建一个 07:30 智能工作日闹钟 → 覆盖安装当前版本（DB version 2）→ 结果：**闹钟仍在、无崩溃、编辑页振动强度显示"中"**（即从迁移后的库读到了新列），点"强"可正常选中。证明迁移未丢数据。

> 说明：原本写了基于 `androidx.room:room-testing` 的迁移单测，但该依赖内部的 kotlinx.serialization 版本与 Room 2.8.5 冲突（`AbstractMethodError`），故改为上述"真机双版本升级"验证，并把该依赖移除，避免给项目引入不可控的传递依赖。
## 6. 已知未覆盖项

- 真实跨设备 NSD 发现（模拟器只覆盖了 127.0.0.1 回环；真机需同热点）
- 连续 3 晚真实起床数据（自适应建议需要样本，报告里用合成数据验证算法）
- 厂商 ROM 后台限制（Huawei/Xiaomi 等，需真机加白）
