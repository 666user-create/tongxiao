# Tongxiao 执行与编码计划（v1）

配套设计文档：`docs/design.md`（已评审）。本计划把 M1~M6 拆成 **26 个原子步骤**，
每步骤 = 实现 → 构建 → 测试 → 双重 review → 提交。**任何一步不过关卡，禁止进入下一步。**

## 0. 每步关卡协议（Definition of Done）

每个步骤结束时依次通过 5 道关卡，全绿才 commit：

| 关卡 | 内容 | 防什么 |
|---|---|---|
| G1 构建 | `gradlew build`（含 lintDebug，**不止 assembleDebug**）零错误零新警告 | 编译期低级错误、lint NewApi 类阻断（Step 1.1 教训） |
| G2 单测 | 该步骤声明的单元测试全绿；纯逻辑必须先写测试再写实现 | 逻辑错误、回归 |
| G3 范围自查 | diff 只含本步骤文件；无计划外抽象/配置项；命名与 design.md 术语一致（Alarm 不叫 Reminder、grantedMask 不叫 permission） | 顺手改出的隐性破坏、过度设计 |
| G4 独立评审 | 用 fresh-eyes 评审代理审本步 diff，按下方「G4 评审清单（8 问）」出【问题/严重度】列表；高危问题清零后才能提交 | 自己看不见的盲点 |
| G5 运行验证 | 该步骤声明的手动验证（模拟器行为、dumpsys、截图比对） | 编译通过但跑不对 |

**G4 评审清单（固定 8 问）**：
1. 崩溃面：空指针/越界/类型转换，尤其 Intent extras 与 JSON 解析入口
2. 线程面：主线程 IO？BroadcastReceiver 里做长活（应 goAsync 或转 Service）？
3. 生命周期：Service/Activity 泄漏（Receiver 注册未反注册、Sensor 未 unregister）
4. Android 版本面：targetSdk 36 的前台服务类型、exact alarm 权限、POST_NOTIFICATIONS 是否处理
5. 数据面：Room 迁移策略、事务边界、枚举落库值稳定
6. 幂等面：重排闹钟/重连/重复消息会不会双份执行
7. 安全面（网络步骤）：接收端钳制是否真的不信任发起端
8. 简单面：有没有为"将来可能"写的死代码（karpathy 规则：删）

## 0.1 全局约定（先定死，防口径漂移）

- 工程目录 `D:\Android\tongxiao\app`（仓库已在 git，docs 先行）
- 工具链锁死：AGP 8.13.2 / Gradle 8.14.3（wrapper 用腾讯云镜像）/ JDK 17（`D:\Android\jdk-17`）/ compileSdk 36 / minSdk 26
- 语言 Java 为主；不引入 Kotlin、不引入 Hilt（用 Application 手动注入单例，课程项目够用且答辩好讲）
- 依赖白名单：material、**appcompat**、room-{runtime,compiler}、lifecycle-{viewmodel,livedata}、work-runtime、gson、constraintlayout。**测试栈例外（已批准）**：androidx.test:{ext:junit,runner,core} 仅 androidTest 作用域、不进 APK；room-testing 暂不引入（无迁移测试，Step 6.x 需要时再加）。加其他新依赖须走 G3 例外审批（问你）
- 包结构：`com.course.tongxiao.{data,engine,alarm,group,ui,stats}`
- 提交规范：`feat(step 2.3): RingService 前台服务与响铃链路` —— 一步一 commit，坏了 revert 一步
- 模拟器策略：AVD `Pixel_7_API_36` 一台主用；网络步骤起第二台（`Pixel_7_API_36_b`，M4 前创建）

## 阶段 1 —— 骨架与数据层

**Step 1.1 工程骨架**
- 产出：根 gradle 配置、settings.gradle、app 模块、Manifest 空壳、MainActivity 占位、主题色板（照抄 03-material-fresh：#006D40/#C8E6C9/#F6FBF4）
- 验证：G1；G5=模拟器启动白屏不崩溃+截图
- 风险点：wrapper 镜像、JDK 路径 —— 直接复用 verify-app 已验证的配置

**Step 1.2 Room 全量 schema**
- 产出：6 实体+6 DAO+`TongxiaoDb`（design.md §3 字段一比一）；`fallbackToDestructiveMigration`（课程项目，v1 不做迁移链）
- 验证：G2=每 DAO 至少 1 条 insert/query/update 内存库测试；G5=schema JSON 导出比对字段
- 评审重点：枚举落库用 TypeConverter 存稳定字符串，不存 ordinal

**Step 1.3 Repository + 配置存储**
- 产出：`AlarmRepository`、`SettingsStore`(SharedPreferences 封装：默认挑战配置/城市/总闸)
- 验证：G2 单测（含 LiveData 观察）

## 阶段 2 —— 闹钟引擎（项目地基，最严审）

**Step 2.1 HolidayResolver（纯函数）**
- 先写测试：2026 国庆/元旦/春节/清明 + 补班日 + 边界（假期内补班?、12-31）≥14 用例
- 产出：resolver + `holidays-2026/2027.json`（按 2026 年公开安排录入，答辩注明数据口径）
- 验证：G2 全绿才允许实现转正（TDD 步骤）

**Step 2.2 AlarmScheduler**
- 产出：schedule/cancel/rescheduleAll；`setAlarmClock` + 唯一 requestCode 规则 + `PendingIntent.FLAG_IMMUTABLE`；SCHEDULE_EXACT_ALARM 运行时检查
- 验证：G5=**无 UI 阶段用 instrumentation 测试直接向 Room 插一条闹钟并调 schedule()**，`dumpsys alarm | findstr tongxiao` 看到注册项；改 5 分钟后再 dump 确认替换而非叠加（幂等）

**Step 2.3 响铃链路：Receiver→RingService→StopRingActivity**
- 产出：AlarmReceiver（goAsync+executor，禁止主线程查库；校验 uid 防陈旧注册）；RingService（前台服务 **type=mediaPlayback**〔平台无 alarm 类型〕、MediaPlayer 铃声循环+振动+WakeLock、环形通知含 fullScreenIntent 与"停止"action）；StopRingActivity（showWhenLocked/turnScreenOn、返回键等价停铃、onNewIntent 重定向、先停声后写库）；RingActions（停铃重排/ONCE 自关/贪睡钳制 1~30/跳过重排）；**首次申请 POST_NOTIFICATIONS 与通知渠道**
- 验证：G5=RingService 前台服务+通知断言（fullScreenIntent/category/stop action）、StopRingActivity 用 ActivityScenario 验证渲染与 STOPPED 事件、返回键路径；**Android 10+ 后台禁 startActivity，全屏页只在锁屏/熄屏由系统拉起（亮屏时走通知）**
- 评审重点：停铃入口闭环（通知 action+返回键+null 行）、startForeground 5s 契约、每闹钟铃声/振动落地、贪睡绕开节假日判定

**Step 2.4 系统事件恢复**
- 产出：BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED 接收器 → rescheduleAll；Manifest 权限补齐
- 验证：G5=`adb reboot` 后 dumpsys 确认闹钟恢复

## 阶段 3 —— 主 UI

**Step 3.1 闹钟列表页**：RecyclerView+Switch+顶部"明天是工作日/法定假"副标题（接 2.1）+空态；ViewModel/LiveData 驱动
- 验证：G5=增删改开关全走查+截图比对预览稿
**Step 3.2 编辑页**：TimePicker+重复模式四选一+自定义星期 dayMask+skipHolidays+标签+贪睡间隔+振动+铃声(系统选择器)+保存即排程/删除即取消；FAB 与行点击入口
- 验证：G5=保存后 DB 落库且 dumpsys/注册断言；G2=配置序列化往返
- 口径说明：**挑战区 UI 归 Step 4.2、天气提前 UI 归 Step 6.2**（各自引擎就绪后再接，避免占位死代码）
**Step 3.3 底部导航+设置页壳**：3 tab 骨架、权限总闸、节假日导入入口（仅 UI 占位，下载逻辑在 Step 6.2 一并实现）

## 阶段 4 —— 挑战系统

**Step 4.1 ChallengeEngine（纯逻辑，TDD）**
- 产出：算术题生成器（确定性种子可测）、题库加载（`quiz-bank.json` 内置 ~100 题：常识/逻辑/急转弯+难度标签）、判分、答错策略（重试/+30s/升难）
- 验证：G2=生成器 1000 次抽样无重复选项/答案分布均匀；钳制函数单测（>5题→拒绝）
**Step 4.2 UnlockActivity**：选择题 UI+进度+答错反馈+长按 3 秒紧急停止（不可配置移除）；**停铃页显示"贪睡第 N 次"**（N 取当日该闹钟 SNOOZE 事件计数，承接 design.md §7③）
- 验证：G5=响铃→挑战→通过/失败/紧急停止三路径
**Step 4.3 摇一摇**：SensorManager 阈值计数+进度环；onPause 必 unregister（评审清单第 3 条重点）

## 阶段 5 —— 宿舍组（网络，安全面重点审）

**Step 5.1 协议编解码（纯逻辑，TDD）**：消息模型+换行 JSON 编解码；坏行丢弃不断连；G2=边界用例（半包/粘包/未知type/超长行）
**Step 5.2 NSD + TCP**：NsdDiscovery、TcpServer/Client（线程模型：单 accept+每连接 reader）、GroupManager（建组/组码/HELLO 握手/心跳/30s 判离线/退避重连）
- 验证：G5=双模拟器互见+HELLO 日志；第二台 AVD 在本步创建
**Step 5.3 权限模型落地**：grantedMask 校验、接收端钳制、pending_requests（2h 过期扫描）、op_log 落 alarm_events
- G2=钳制与过期逻辑表驱动测试；G4 重点=清单第 7 问（不信任发起端）
**Step 5.4 CMD_MUTE 全链路**：ringingNow 上报→对端静音按钮→执行→ACK/NOTIFY_OP 双留痕
- 验证：G5=双模拟器实测 P50<2s；离线/非响铃拒绝路径
**Step 5.5 宿舍页 UI + SET_REQUEST 确认流**：成员列表/通知流/待确认卡（同意→本地重排）；代设闹钟完整走查（在线确认+离线挂起+醒来补确认）

## 阶段 6 —— 智能增强与加固

**Step 6.1 AdaptiveEngine（TDD）**：中位数统计+建议生成（连续5天阈值）+冷启动静默；G2=构造事件序列断言
**Step 6.2 WeatherClient**：Open-Meteo 拉取+weather_cache+24h 失效+失败静默；G2=解析测试（mock JSON）；G5=断网演练不崩
**Step 6.3 统计页**：自绘折线（起床趋势）+贪睡榜+被静音趣味榜
**Step 6.4 保活加固**：WorkManager 6h 自检补排；品牌检测+小米/华为图文引导页
**Step 6.5 ContentProvider（加分项，可选）**：对外只读 `content://com.course.tongxiao.alarms`

## 阶段 7 —— 验收

**Step 7.1 测试矩阵执行**：design.md §9 全表跑一遍（锁屏/来电打断/Doze/改时间/杀进程重启/断网退组），结果记录进 `docs/test-report.md`
**Step 7.2 连续 3 晚真机响铃**（你配合充电放宿舍，或模拟器长挂）
**Step 7.3 文档收尾**：README、答辩 PPT 大纲（考点对照表→附录 A 直接搬）、代码注释终查

## 里程碑映射

| 里程碑 | 步骤 | 完成判据 |
|---|---|---|
| M1 骨架+引擎 | 1.1~2.4 | 模拟器响铃+重启恢复 |
| M2 智能工作日 | 3.1~3.3（含 2.1 联调） | 国庆不响/补班响演示 |
| M3 挑战 | 4.1~4.3 | 答题停铃演示 |
| M4 宿舍组 | 5.1~5.5 | 双机静音+代设确认演示 |
| M5 完整度 | 6.1~6.5 | 全功能 |
| M6 提交 | 7.1~7.3 | 测试报告+答辩材料 |

## 防返工三条铁律

1. **步骤串行，禁止跳步**：后步依赖前步的验证结论（如 5.4 依赖 2.3 的 ringingNow 真实可用）
2. **每步 G4 独立评审用无上下文代理**：只喂 diff + design.md 相关节 + 8 问清单，评审者不知道"我"为什么这么写，才能抓到盲点；高危问题修完才 commit
3. **口径唯一**：字段/消息/枚举名以 design.md 为准，实现里要改设计先改文档再改码（文档与代码同 commit），防止答辩时 PPT 和代码对不上
