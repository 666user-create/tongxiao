# 同晓 · 测试报告

> 生成方式：本文件由用户在模拟器 `Pixel_7_API_36` 上实跑的 `gradlew connectedDebugAndroidTest` 结果与各里程碑关卡记录汇总。
> 状态：**已完成**（最终全量运行：279 tests / 0 failed / 0 skipped，模拟器 Pixel_7_API_36）

## 1. 结论速览

| 项目 | 结果 |
|---|---|
| 单元/集成测试（instrumentation） | **279 tests / 0 failed / 0 errors / 0 skipped** |
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
| `RegionCatalogTest`(25) `RegionPickerDialogTest`(5) `RegionWheelControllerTest`(7) | 37 | 滚轮级联与安全性（含"省列表变短导致越界"崩溃回归）、 三级行政区目录（34/365/3094，含港澳台）、坏 JSON 降级、直辖市/特区折叠、无区县市隐藏第三轮、滚轮回填（两段路径） |
| `MessageCodecTest`(11) `GroupProtocolTest`(7) `RequestPayloadsTest`(7) `PeerLinkTest`(6) `GroupManagerTest`(6) `GroupCoordinatorTest`(28) `GroupCleanupTest`(4) `NsdDiscoveryTest`(4) `GroupIdentityTest`(3) `GroupTabTest`(2) | 78 | 半包/粘包/超长/未知类型、HELLO 握手、越权拒绝、待确认请求生命周期、退组清理、宿舍页 tab 接线 |

## 4. 手动验证（G5）记录

| 场景 | 操作 | 观察结果 |
|---|---|---|
| 保存闹钟 | 新建 → 底部保存 | 回列表并出现条目，Toast 显示"下次响铃：…" |
| 时间选择 | 点大时间 | 双滚轮（`TimePicker` + 2 个 `NumberPicker`），确定后回填 |
| 边缘适配 | 进入各页 | 工具栏 top=136px（=状态栏高度），底部导航避开手势条 |
| 响铃链路 | 编辑页"试响（10 秒后）" | 到点拉起停铃页（logcat: `Displayed ...StopRingActivity`） |
| Provider | `adb shell content query --uri content://com.course.tongxiao.alarms/alarms` | 游标可用；`content insert` → UnsupportedOperationException |

## 5. 评审抓出的真实缺陷（可作为答辩素材）

1. **镜像表主键用自增 id** → 对端重装后会静音错闹钟：改为 `srcAlarmUid + uid` 唯一索引，找不到返回 `ALARM_NOT_FOUND`
2. **`deleteWithEvents` 接受 id=0** → 静默删掉 `alarmId=0` 的事件桶：前置抛 `IllegalArgumentException`
3. **停铃页返回键/空行导致铃声无法停止**（BLOCKER）→ 通知加"停止" action、返回键等价停铃、预测式返回处理
4. **`DAILY` 曾被 `skipHolidays` 静默跳过 33 个假日日** → 口径改为 DAILY 恒响，并写回设计文档
5. **`startForegroundService` 5 秒契约未满足** → `RemoteServiceException` 杀进程：改为先 `startForeground` 再校验
6. **Provider `Uri.getPath()` 带前导斜杠** → 拒绝自己的 URI：改用 `getPathSegments()`
7. **`StatsFragment` 只在 onResume 加载** → 宿主 show/hide 时永远空白：改为 onViewCreated 先加载 + onHiddenChanged 刷新

## 6. 已知未覆盖项

- 真实跨设备 NSD 发现（模拟器只覆盖了 127.0.0.1 回环；真机需同热点）
- 连续 3 晚真实起床数据（自适应建议需要样本，报告里用合成数据验证算法）
- 厂商 ROM 后台限制（Huawei/Xiaomi 等，需真机加白）
