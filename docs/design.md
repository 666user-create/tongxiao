# 同晓（Tongxiao）— 智能闹钟 App 设计文档

- 课程：Android 应用开发 课程设计（期末题目）
- 日期：2026-10-27
- 状态：已评审（分节确认通过）
- 实现语言：**Java**（与课程内容对齐）；架构：经典 MVVM（Activity/Fragment + ViewModel + LiveData + Room）
- UI 风格：**Material 清新（风格 3）**，预览稿见 `D:\Android\ui-preview\03-material-fresh.html`
- 目标设备：Android 8.0+（minSdk 26），targetSdk 36；开发环境 `D:\Android`（SDK 36 / JDK 17 / Gradle 8.14.3 / AGP 8.13.2）

---

## 1. 产品定义

### 1.1 要解决的问题

| 痛点 | 现状 | 本方案 |
|---|---|---|
| 节假日/调休要手动改闹钟 | 原生闹钟只认星期，国庆照响、补班日漏响 | **智能工作日**：法定假自动跳过，调休补班日照常响 |
| 闹钟设不准自己的真实起床点 | 靠感觉设，天天贪睡 | **行为自适应**：统计贪睡/停铃记录，建议更合理的时刻 |
| 关闹钟太容易，按一下就睡回去 | 原生停铃=解锁屏幕 | **停铃挑战**：选择题/摇一摇，可配置难度 |
| 天气突变早高峰需要更早出门 | 人肉判断 | **天气联动**：恶劣天气自动提前 |
| 宿舍互相干扰：奇怪闹钟吵醒全寝 | 只能口头抱怨 | **局域网组员互助**：同 Wi-Fi 绑定，远程静音扰民闹钟 |
| 组员不在时忘设闹钟导致睡过头 | 无解 | **代设闹钟**：组员帮设，本人确认后生效 |

### 1.2 功能范围（v1 全部实现）

1. 闹钟基础功能：多闹钟、重复规则、贪睡、铃声/震动、开机恢复
2. 智能工作日引擎（节假日/调休自动跳过与照常）
3. 行为自适应建议（只建议，不自动改）
4. 停铃挑战：选择题（1~5 题、多题型池）/ 摇一摇 / 直接停，全局默认 + 每闹钟覆盖
5. 天气联动提前（缓存兜底，宁可不提前）
6. 互助组（局域网 P2P）：成员发现、时刻表共享、远程静音（免确认）、代设闹钟/改题目（需确认）、操作留痕
7. 统计页：起床趋势、贪睡排行、被静音趣味榜
8. 后台可靠性：自检补排、权限/保活检查提示（**常驻"明早 06:40"通知与厂商白名单引导 v1 未实现**，列入展望）

### 1.3 明确不做（防范围蔓延）

- 睡眠周期渐醒（传感器判断深浅睡）——工程不可控，答辩风险
- 云端账号体系/远程服务器——已定局域网 P2P
- iOS/桌面端
- 端侧 ML（TFLite 预测起床）——写进"展望"章节即可

---

## 2. 总体架构

```
┌──────────────────────── UI 层 ─────────────────────────┐
│ MainActivity(闹钟列表) EditAlarmActivity(编辑)          │
│ StopRingActivity(全屏停铃) UnlockActivity(挑战任务)     │
│ GroupActivity(互助组) StatsActivity(统计) SettingsActivity│
└──────────────────────────┬─────────────────────────────┘
                 ViewModel + LiveData（数据驱动 UI）
┌──────────────────────────┴─────────────────────────────┐
│ ①闹钟引擎   AlarmScheduler → AlarmReceiver → RingService │
│ ②智能决策   HolidayResolver │ AdaptiveEngine │ WeatherClient │
│ ③组内协作   GroupManager │ NsdDiscovery │ P2pServer/Client │
│ ④数据层     Room(6表) │ assets 节假日 JSON │ SharedPreferences │
│ ⑤保活       前台服务常驻通知 + 电池优化白名单引导          │
└────────────────────────────────────────────────────────┘
```

### 2.1 模块与课程考点映射（答辩用）

| 模块 | 职责 | 考点 |
|---|---|---|
| 闹钟引擎 | AlarmManager.setAlarmClock 精确触发 → BroadcastReceiver → 前台 Service 播铃+震动 → 全屏停铃页 | AlarmManager、Service、BroadcastReceiver、Notification、WakeLock |
| 智能决策 | 响前查节假日表；贪睡统计建议；天气提前 | JSON 解析、文件 IO、统计算法、HttpURLConnection |
| 组内协作 | NSD 发现 + TCP 指令（静音/代设/改题） | 网络编程、多线程 Socket、Binder 无关 |
| 数据层 | Room 持久化；ContentProvider 对外共享闹钟数据（加分项） | Room、ContentProvider、SQLite |
| UI | Material 3 组件全套 | 布局、自定义 View（挑战卡） |
| 保活 | 厂商 ROM 适配、开机重排、自检 | 前台服务、权限、系统广播 |

### 2.2 核心数据流（响铃链路）

```
AlarmManager 到点
  → AlarmReceiver.onReceive()（仅转发，禁止内联 DB）
  → 后台线程查 Room 取 Alarm → HolidayResolver.shouldFire(alarm, today)?
      ├─ 否 → 写 alarm_events(SKIPPED_HOLIDAY) + **重排下一次**，不响
      └─ 是 → WeatherClient 检查提前量（仅智能工作日闹钟）
             → startForegroundService(RingService)
             → RingService: 播放铃声/震动 + 拉起 StopRingActivity(FLAG_SHOW_WHEN_LOCKED)
             → 按配置延迟 N 秒弹 UnlockActivity(挑战)
             → 挑战通过 / 紧急停止 / 静音 → 停铃，写 alarm_events(STOPPED)
                 → **重复闹钟（DAILY/CUSTOM/WORKDAY_SMART）：立即 schedule(下一次)**
                 → **ONCE：置 enabled=false，不再重排**
             → 贪睡 → 重排 setAlarmClock(+snoozeMin)，写 alarm_events(SNOOZE)
```

**响后必须重排**：`setAlarmClock` 是一次性注册，`AlarmManager` 不会自己续期。若只依赖 §6.4 的 6h 自检，force-stop 或自检未跑到就会漏响。因此停铃路径（含静音/紧急停止）必须同步调用 `AlarmScheduler.schedule()` 计算下一次；§6.4 自检只作为兜底。

**贪睡语义**：贪睡不是"改闹钟日程"，而是用同一个 requestCode 调 `setAlarmClock(now+snoozeMin)` **覆盖**当前注册——下一次常规闹钟在"最终停铃"时才重新排上。所以贪睡重响的 intent 带 `EXTRA_FROM_SNOOZE`，接收端**跳过节假日/星期判定**直接响（否则 23:55 贪睡跨零点到假日会被静默吞掉，违反 §8.1）；snoozeMin 钳制 1~30 分钟。

**Android 10+ 平台限制（答辩必讲）**：后台应用禁止直接 `startActivity`，因此停铃全屏页**不能**靠 `startActivity` 拉起；可靠路径是响铃通知携带 `fullScreenIntent`，由系统在**锁屏/熄屏**时展示。亮屏解锁时用户看到的是环形通知（含"停止"action），点通知进入停铃页。这是安卓闹钟的标准交互，不是实现缺陷。

**停铃入口必须闭环**：环形通知常驻"停止"action（`PendingIntent.getService`，不受后台启动限制）+ 停铃页返回键等价停铃 + 闹钟行已删除时也要先停服务再退出。三者缺一，铃声就可能无法停止。

---

## 3. 数据模型（Room，库名 `tongxiao.db`）

### 3.1 表

**alarms — 闹钟本体**

| 列 | 类型 | 说明 |
|---|---|---|
| id | int PK auto | 本机自增，仅本地使用 |
| uid | text | **稳定标识（UUID，创建时生成）**。跨设备引用一律用 uid（自增 id 在重装/破坏式重建后会重编，跨设备引用会错位） |
| label | text | 闹钟名（"早八"/"跑步"），列表、停铃页、SYNC_PUSH 展示 |
| hour / minute | int | |
| dayMask | int (7bit) | 周一~周日 |
| repeatMode | enum | ONCE / DAILY / CUSTOM / WORKDAY_SMART |
| | | **ONCE 语义**：= "下一个该时刻响一次"（今天该时刻未过则今天，否则明天），响后自动置 enabled=false；不按节假日规则跳过。v1 不做"顺延到下一工作日"（列入展望）。 |
| skipHolidays | bool | **仅 CUSTOM 生效**。唯一口径：WORKDAY_SMART 恒按节假日跳（它是"智能工作日"的定义）；DAILY 恒响（"每天"就是每天，含法定假）；ONCE 不看节假日；CUSTOM 由本开关决定。 |
| skipHolidays | bool | 仅 WORKDAY_SMART/CUSTOM 生效：法定假跳过 |
| ringtoneUri / vibrate | text / bool | |
| snoozeMin | int | 贪睡间隔，默认 10 |
| challengeOverrideJson | text nullable | null=用全局默认；否则该闹钟专属挑战配置 |
| advanceEnabled | bool | 天气提前开关 |
| advanceMaxMin | int | 最多提前分钟数（5~20） |
| enabled | bool | |
| sharedToGroup | bool | 是否把本闹钟同步进组内时刻表 |

**alarm_events — 触发/行为日志**（喂自适应引擎 + 统计页 + 操作留痕）

| 列 | 类型 | 说明 |
|---|---|---|
| id | int PK | |
| alarmId | int（**逻辑外键**→alarms.id，无 DB 约束；删闹钟由 Repository 同事务清理事件，见 §3.3） | |
| fireAt / stopAt | long | epoch millis |
| type | enum | FIRED / SKIPPED_HOLIDAY / SKIPPED_REASON / SNOOZE / STOPPED / EMERGENCY_STOP / CHALLENGE_FAIL |
| meta | text(JSON) | 贪睡第几次、答题耗时/错误次数、天气原因、操作来源设备 |

**group_members — 互助组成员**

| 列 | 类型 | 说明 |
|---|---|---|
| deviceId | text PK | 稳定 UUID（首启生成） |
| nick | text | 昵称 |
| groupKey | text | 6 位组码 |
| grantedMask | int | **我授予该成员的权限**：bit0 静音我 / bit1 可见我时刻表 / bit2 可向我发起代设·改题 |
| online / lastSeenAt | bool / long | 断连 30s 判离线 |
| joinAt | long | |

**remote_alarms — 组员时刻表镜像（只读）**

| 列 | 类型 | 说明 |
|---|---|---|
| ownerDeviceId | text（逻辑外键→group_members.deviceId） | |
| **srcAlarmUid** | text | **对方 alarms.uid**（协议携带，见 §4 CMD_MUTE 定位）。用 uid 而非自增 id：对方重装/破坏式重建后 id 会从 1 重编且可能命中**另一个**闹钟；旧 uid 则查不到（回 ALARM_NOT_FOUND），绝不会静音错闹钟 |
| alarmLabel / timeText / repeatText | text | 展示用 |
| enabled / ringingNow | bool | ringingNow 决定"静音"按钮可用性 |
| syncedAt | long | |

主键：(ownerDeviceId, srcAlarmUid)。SYNC_PUSH 全量替换该 owner 的镜像行（替换前接收端强制改写每行 ownerDeviceId，防跨 owner 污染）。

**pending_requests — 待确认请求（改题目/代设闹钟）**

| 列 | 类型 | 说明 |
|---|---|---|
| reqId | text PK | |
| fromDeviceId | text | |
| type | enum | SET_CHALLENGE / ADD_ALARM / REMOVE_ALARM |
| payloadJson | text | 已按接收端规则**钳制后**的配置 |
| createdAt / expireAt | long | 默认 2h 作废 |
| status | enum | PENDING / ACCEPTED / REJECTED / EXPIRED |

**weather_cache — 天气预报缓存**

| 列 | 类型 | 说明 |
|---|---|---|
| city | text PK | 手动输入的城市 |
| date | text | 预报对应日期 |
| condition / minTemp | text / int | 恶劣判定输入 |
| fetchedAt | long | 超 24h 视为无效 |

全局默认挑战配置、定位城市、组权限总闸存 SharedPreferences（`tongxiao_settings`），口径固定：

| 键 | 默认 | 语义 |
|---|---|---|
| `default_challenge_json` | `null` | null = 继承 ChallengeEngine 内置默认；每闹钟覆盖仍在 `alarms.challengeOverrideJson` |
| `weather_city` | `""` | 空 = 天气功能关闭（不请求、不提前） |
| `group_master_on` | `true` | 组权限总闸，见 §3.2 |

### 3.2 权限模型（组内协作的安全边界）

两级语义——**这是本设计的核心决策**：

- **响铃中的闹钟 → 静音/停止：免确认，立即执行 + 双方留痕 + 通知**。理由：扰民场景（凌晨奇怪闹钟吵醒组员）里本人不可能在线确认，等确认=功能失效。
- **设置类（改我的挑战配置 / 代我设·删未来闹钟）：必须走 pending_requests，本人点同意才生效**。理由：这是"门锁"，防恶搞防误伤。

硬边界（接收端强制，不信任发起端）：
- 挑战参数钳制：题数≤5、选项≤4、必须保留紧急停止；越界直接拒绝并回错误码
- 静音指令只对 `ringingNow=true` 的闹钟生效，且要求 TCP 连接 60s 内有心跳
- 所有远程操作写 `alarm_events.meta`（来源设备+操作类型），互助页"操作通知流"可见
- 设置页总闸（`group_master_on`）：**协议层闸门，不改数据**。关闭后 `PeerLink` 一律拒绝执行 CMD_MUTE / SET_REQUEST（回 `CMD_ACK ok=false, reason=MASTER_SWITCH_OFF`），但 `grantedMask` 保持原样——避免"关→开"后所有授权丢失。要真正收回某人权限，用成员详情里的逐项授权开关（`setGrantedMask`），两者职责分离。

代设闹钟的离线场景（组员在家帮你设明早考试闹钟，你在宿舍睡着）：请求挂起 `pending_requests`，2 小时无人值守自动作废；若你醒来（app 前台）仍有 PENDING，弹补确认。**不做"永远不生效"也不做"偷偷生效"。**

### 3.3 外键与删除语义

所有表间引用为**逻辑外键**（无 DB 级 FOREIGN KEY 约束），原因：Room 会对未加索引的 FK 列报 warning，且 v1 数据量下约束收益低。代价由规则补偿：

- 删除 `alarms` 行时，Repository 必须**同事务**删除其 `alarm_events`（`AlarmEventDao.deleteByAlarm`），防孤儿事件污染 AdaptiveEngine 统计与统计页。
- 删除 `group_members`（退组）时，同事务删除该 owner 的 `remote_alarms`（`clearOwner`）与 `pending_requests`（`deleteBySender`）。
- 以上清理只发生在 Repository 层；DAO 的 delete 方法 javadoc 注明"勿绕过 Repository 直接调"。

---

## 4. 局域网协作协议

- 发现：`NsdManager` 注册/浏览 `_tongxiao._tcp`，实例名=昵称；加入组=输入 6 位组码（建房页生成，握手时校验）
- 传输：TCP 长连接（持有方=本机后台常驻），消息=**换行分隔 JSON**，一行一条
- 拓扑：全连接 mesh（4 人寝 ≤ 3 条对端连接，规模无压力）

| type | 方向 | 载荷 | 语义 |
|---|---|---|---|
| HELLO | 双向 | dev, nick | 握手，验组码 |
| BYE | 双向 | — | 优雅断开 |
| SYNC_PUSH | 主动 | alarms:[{uid,label,time,repeat,enabled,ringing}] | 推自己时刻表，uid=本机 alarms.uid（需对方授予 bit1 可见） |
| CMD_MUTE | 发起→执行 | ownerDev, alarmUid | 静音响铃中闹钟（alarmUid 来自 SYNC_PUSH，执行端按 uid 查本机 alarms），立即执行；查不到回 CMD_ACK{ok:false, reason:ALARM_NOT_FOUND} 并令对端重发 SYNC_PUSH 刷新镜像 |
| CMD_ACK | 执行→发起 | ok, reason | 回执 |
| NOTIFY_OP | 执行→发起 | what, at | 操作回执通知（"你静音了小李的 06:40"） |
| SET_REQUEST | 发起 | reqId, type, payload | 改题目/代设/删闹钟申请 |
| SET_RESPONSE | 接收方 UI 触发 | reqId, accept | 同意才落库重排 |
| PING | 双向 | ts | 60s 心跳，判在线 |

错误处理：JSON 解析失败→丢弃该行不断连；未知 type→忽略+日志；连接断→指数退避重连（2s/8s/30s），30s 无心跳标记离线。

---

## 5. 智能引擎

### 5.1 节假日/调休引擎 HolidayResolver

- 数据：`assets/holidays-2026.json`、`holidays-2027.json`。结构：
  ```json
  { "year":2026,
    "holidays":[ {"name":"国庆节","from":"2026-10-01","to":"2026-10-07"} ],
    "workdays_extra":[ {"date":"2026-09-20","name":"国庆调休补班"} ] }
  ```
- 判定 `shouldFire(alarm, date)`（纯函数，单测覆盖），按 repeatMode 分派：
  - `WORKDAY_SMART`：`date ∈ workdays_extra` → **响**（补班日照常，哪怕周六）；`date ∈ holidays` → **跳**；否则周一~周五响
  - `CUSTOM`：`skipHolidays=true` 且 `date ∈ holidays` → 跳；否则按 dayMask
  - `DAILY`：恒响（"每天"含法定假，用户可自行关闭）
  - `ONCE`：不看节假日，见 §3.1 ONCE 语义
- v1 **不做**"一次性闹钟顺延到下一工作日"（列入 §11 展望）；`nextWorkdayAfter` 一并移除，避免死代码
- 更新：设置页"从链接导入 JSON"（URL → HttpURLConnection → schema 校验 → 写 cacheDir 副本，运行时优先读 cache）；每年 11~12 月公告后手动更新，**不依赖任何第三方 API 存活**
- 兜底：数据缺失**或解析失败** → 退化为普通星期逻辑 + 列表页黄条"节假日数据未更新"（`HolidayCalendar.isDegraded()`）；解析采用"先解析到临时集、成功再合并"，坏文件不会留下半张表

### 5.2 行为自适应 AdaptiveEngine

- 输入：最近 14 天 `alarm_events`（FIRED→STOPPED 间隔、SNOOZE 次数与时长）
- 算法（答辩写伪代码）：对每个闹钟取窗口内"最终停铃时刻"中位数 E、"首次贪睡时刻"中位数 M；
  - 连续 5 天 E ≥ 设定+15min → 建议卡"改到 round(E)"
  - E ≤ 设定-10min 且无贪睡 → 建议"可晚 10 分钟"
- **只建议不自动改**（信任红线），一键采纳/忽略
- 冷启动：样本 <5 天不弹，显示"继续记录中"

### 5.3 天气联动 WeatherClient

- 数据源：Open-Meteo（免 key）；定位=设置页手动输入城市（不申请定位权限，隐私答辩好讲）
- 时机：22:00 定时拉明日预报 → Room 缓存（weather_cache，含 fetchedAt）
- 判定：闹钟触发前读缓存（≤24h 有效），命中恶劣（雨/雪/低温，阈值用户可配）→ 在 advanceMaxMin 内提前 5~20 分钟；缓存过期 → **不提前**（宁可不提前也不用过期数据）
- 失败静默，绝不影响主链路

### 5.4 停铃挑战 ChallengeEngine

- 题型池：①算术选择题（本地随机生成：时间/数字运算，错误选项取邻近干扰项，**完全离线**）②常识/逻辑/头脑题（assets 内置 30 题，带难度标签，可扩充）③**自定义题目**（用户自己写题干 + 4 选项 + 正确项，最多 50 道，存 SharedPreferences）
- **默认关闭**：挑战是用户显式开启的可选项（编辑页三选一：关闭 / 选择题 / 摇一摇）；开启后默认 QUIZ 2 题\n- **题目来源按闹钟可选**：`ChallengeConfig.questionSource = BUILT_IN | MY_OWN`（默认 BUILT_IN）。选"我的题目"时只用用户自出的题；**若还没出过题则自动回落到内置题库**（不能出现"答不出的挑战"）。该字段随 `challengeOverrideJson` 一起存储与同步——所以组内代设这个闹钟时，题目来源也会一起生效
- 配置维度：模式(DIRECT/摇一摇/选择题)、题数 1~5、类型组合、**答错惩罚**、题目来源、弹出延迟 0~30s —— **接收端强制钳制**，来自对端的配置一律 clamped()\n- **答错惩罚三种，都真实生效（按闹钟可选）**：①**原地重试**——同一题重新答，无额外代价；②**锁定冷却**——选项禁用 `max(5, 延迟+10)` 秒后换下一题；③**加一道难题**——立即追加一道难度 3 的算术题，最多追加 2 道（保证挑战终会结束）。答错次数始终显示在进度行（"第 1 / 2 题 · 已答错 2 次"）
- 摇一摇：`SensorManager` 加速度计数阈值+进度环
- 紧急停止：长按（标准长按 ~1 秒，文案不写具体秒数；不可被任何配置移除），记 EMERGENCY_STOP 供自适应分析
- 挑战模块任何异常 → 直接停铃（宁可不拦，不能困人）

---

## 6. 后台可靠性设计

| 风险 | 对策 |
|---|---|
| Doze/省电杀死定时 | `setAlarmClock()`（闹钟级最高优先级，自带白名单语义） |
| 进程重启闹钟丢失 | `BOOT_COMPLETED` 接收器 → Room 全量重排 |
| 用户改时间/时区 | `ACTION_TIME_SET/TIMEZONE_CHANGED` 接收器重排 |
| 厂商 ROM 杀后台 | 设置页品牌检测 → 小米/华为/OV 图文引导（自启动+电池策略）；常驻通知（低优先级，显示"明早 06:40 响"） |
| 静默失败无人知 | 每 6h WorkManager 自检：对比已注册 PendingIntent vs Room 期望，缺则补排并记日志 |
| 响铃时锁屏 | StopRingActivity 带 `setShowWhenLocked/turnScreenOn`；WakeLock 保屏幕与音频 |

---

## 7. UI 规格（Material 清新 · 风格 3）

底部导航 **4 tab**：**闹钟 / 互助 / 统计 / 设置**（统计独立成 tab：自绘趋势图占地大，塞进设置页会挤压城市/互助等常用项）；全屏页：编辑闹钟、停铃、挑战。

### §7.3 竖屏锁定与挑战模式入口（v1 决策）

- **锁竖屏**：四个 Activity 全部 `screenOrientation="portrait"`。理由是横屏实测下（带权限横幅时）闹钟列表只剩不到一行高度，而闹钟是"单手、扫一眼、竖着用"的产品。**注意**：Android 16+ 在大屏（≥600dp）会忽略该请求，所以列表在横屏下额外隐藏"明天是工作日"副标题作为兜底（`AlarmListFragment`）。
- **挑战模式入口**：编辑页用**三选一按钮组**（关闭 / 选择题 / 摇一摇）取代原来的单个开关。此前摇一摇只能由对端 `SET_REQUEST` 或手写 JSON 触发，UI 上不可达；现在两种模式都能从界面直接选，并各自带默认参数（选择题 2 题、摇一摇 5 次）。

| 屏 | 关键元素 | 交互 |
|---|---|---|
| ① 闹钟列表 | TopAppBar 副标题"明天是工作日/法定假"（调休引擎外显）；卡片=大时间+标签胶囊(智能工作日/节假日跳过/补班照常)+Switch；FAB | 长按卡→BottomSheet(删除/复制/共享给组)；数据未更新黄条 |
| ② 编辑闹钟 | Material TimePicker；重复模式四选一；智能工作日展开说明卡；贪睡/铃声/震动；挑战区(继承全局/仅此闹钟)；天气提前开关 | 保存→Scheduler 重排 |
| ③ 停铃页 | 超大时间+闹钟名+贪睡第 N 次；挑战卡(选择题 A~D 圆角条)；摇一摇进度环；底部固定"长按紧急停止"（可滚动区之外，保证任何屏幕都够得到） | 答错按策略处理（重试/冷却换题）；成功→停铃写事件 |
| ④ 互助组 | 未加入：创建/组码加入大卡；已加入：成员行(在线点+**可点的权限chips**+共享时刻表)+操作通知流+待确认请求卡(同意/拒绝) | 成员行直接操作：静音其响铃闹钟(唯一免确认)/申请代设（**不另做 BottomSheet**） |
| ⑤ 设置 | 全局默认挑战；节假日数据(版本+导入链接)；保活引导；组员权限总闸；统计入口 | — |
| ⑥ 统计 | 起床趋势折线(自绘 View)、贪睡排行、被组员静音榜、**互助榜单(离线优先快照)** | — |

### §7.2 互助榜单：离线优先，而不是实时同步

局域网必然断连（熄屏、走出覆盖范围、路由器重启），所以榜单**不能设计成实时数据**。做法是"快照 + 最终一致"：

1. 每台设备本地就算好自己的摘要（近 7 天起床中位时刻、贪睡次数、被静音次数），数据源是本地 `alarm_events`，**不需要联网也有值**；
2. 连上同一 Wi-Fi 且组内在线时，用 `STATS_PUSH` 把这份摘要（几百字节）广播出去；对端**只保留最新的 sentAt**（乱序旧包直接丢弃）；
3. 榜单页面渲染"本机 + 所有收到过快照的成员"，每条显示**同步于多久之前**；超过 24h 的标灰（`stale`），但**永不隐藏**——离线成员仍然在榜上，只是数据旧；
4. 断网时榜单完全可用（读本地快照），重连后自动覆盖，无需手动刷新。

代价与取舍：榜单反映的是"最后一次握手时"的数据，可能滞后；换来的是**不依赖长连接、不依赖服务器、熄屏也能看**。这也是真实产品（如 Keep/微信运动）对离线数据的常见处理方式。


色板照抄 `03-material-fresh.css`：主色 #006D40、容器 #C8E6C9 系、背景 #F6FBF4、大圆角 26dp。

---

## 8. 错误处理总原则

1. **宁响勿哑**：任何智能判断（节假日/天气/挑战）失败 → 退化为普通闹钟照常响
2. **不信任发起端**：所有远程参数接收端钳制
3. **可追溯**：远程操作全量写 `alarm_events.meta`
4. **崩溃兜底**：RingService/挑战页崩溃 → 系统级铃声继续 + 紧急停止始终可用
5. 网络类（天气/节假日导入）失败静默降级，不打扰用户

## 9. 测试计划

- **JUnit 单测**：HolidayResolver（2026 国庆/春节/调休补班用例表 ≥12 条）；AdaptiveEngine（构造事件序列断言建议）；协议编解码（换行 JSON 边界/坏行）
- **Android 测试**：双模拟器+真机跑 NSD 互助全链路（静音/代设/确认流/离线挂起）；`adb shell dumpsys alarm` 验证重启恢复；`adb shell cmd alarm` 模拟 Doze
- **手工矩阵**：锁屏响铃、来电打断响铃、改系统时间、断 Wi-Fi 退组表现、杀进程后 BOOT 恢复、挑战答错循环、紧急停止
- **验收标准**：连续 3 晚真机响铃成功率 100%；组内静音指令 P50 < 2s

## 10. 里程碑（单人节奏，时间充裕版）

| 阶段 | 内容 | 产出 |
|---|---|---|
| M1 | 工程搭建+Room+闹钟引擎+响铃/停铃页 | 普通闹钟可用 |
| M2 | 节假日引擎+智能工作日+列表 UI 完善 | 核心卖点可演示 |
| M3 | 挑战系统（选择题/摇一摇/配置） | 演示爆点 1 |
| M4 | 互助组（NSD+TCP+权限+确认流） | 演示爆点 2（需两台设备） |
| M5 | 自适应+天气+统计+保活加固 | 完整度 |
| M6 | 测试矩阵+文档+答辩材料 | 提交 |

## 11. 展望（写进答辩 PPT，不实现）

端侧 ML 起床预测（TFLite）、睡眠周期渐醒、云端多设备同步、手表震动联动。

---

## 附录 A：与课程考点对照表（答辩用）

AlarmManager.setAlarmClock / 前台 Service / BroadcastReceiver(BOOT/TIME_SET) / Notification(常驻+全屏intent) / WakeLock / Room+DAO+迁移 / ContentProvider(对外共享闹钟数据，加分) / SharedPreferences / HttpURLConnection+JSON / 多线程 Socket+NsdManager / SensorManager / WorkManager 自检 / Material 3 组件 / 厂商 ROM 适配策略。
