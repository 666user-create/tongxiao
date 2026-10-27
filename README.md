# 同晓（Tongxiao）· 智能闹钟

> 懂中国调休的智能闹钟：法定节假日自动跳过、补班日照常唤醒；停铃挑战治赖床；同 Wi-Fi 舍友互相静音扰民闹钟、代设起床闹钟。

- 课程：Android 应用开发 课程设计（期末题目）
- 技术栈：Java · 经典 MVVM（Activity/Fragment + ViewModel + LiveData + Room）· minSdk 26 / targetSdk 36 · AGP 8.13.2 / Gradle 8.14.3 / JDK 17
- 状态：v1 实现完成（M1–M5），测试与演示材料见 `docs/`

---

## 1. 它解决什么问题

| 痛点 | 原生闹钟 | 同晓 |
|---|---|---|
| 节假日 / 调休要手动改闹钟 | 只认星期，国庆照响、补班漏响 | **智能工作日**：法定假跳过，补班日（哪怕周六/周日）照常响 |
| 叫不醒 / 关掉继续睡 | 按一下就关 | **停铃挑战**：选择题（可选 1–5 题）/ 摇一摇；长按 3 秒紧急停止 |
| 起床时间设不准 | 靠感觉 | **行为自适应**：统计停铃/贪睡中位数，给出调整建议（只建议不改） |
| 雨雪天早高峰要更早出门 | 人肉判断 | **天气联动**：恶劣天气自动提前 5–20 分钟（缓存过期则不提前） |
| 宿舍里别人的奇怪闹钟吵醒全寝 | 只能口头抱怨 | **局域网互助**：同 Wi-Fi 绑定宿舍组，可远程静音正在响的闹钟 |
| 舍友不在，忘设闹钟睡过头 | 无解 | **代设闹钟**：组员申请，本人一键同意后生效 |

## 2. 功能与实现位置

| 功能 | 关键类 | 说明 |
|---|---|---|
| 调休/节假日引擎 | `engine/HolidayCalendar`、`engine/HolidayResolver` | assets 内置 2026 国务院公告（33 假日 / 6 补班日），缺失年份自动降级为普通星期 |
| 下次触发计算 | `engine/NextTriggerCalculator` | 补班优先 > 法定假 > 星期；严格"晚于当前"；DST 重叠取后 offset |
| 闹钟调度 | `alarm/AlarmScheduler` | `setAlarmClock`（闹钟级最高优先级）+ requestCode=id 幂等 + 不精确兜底 |
| 响铃链路 | `alarm/AlarmReceiver` → `alarm/RingService` → `alarm/StopRingActivity` | 前台服务播铃+振动+WakeLock；锁屏全屏页；通知常驻"停止" |
| 响后状态机 | `alarm/RingActions` | 停铃重排下一次、ONCE 自关闭、贪睡钳制 1–30 分、跳过重排 |
| 系统事件恢复 | `alarm/SystemEventReceiver` | 开机 / 改时间 / 时区 / 应用更新 → 全量重排 |
| 停铃挑战 | `engine/challenge/*`、`ui/UnlockActivity` | 离线算术题 + 30 题题库；配置接收端硬钳制 |
| 宿舍互助 | `group/*` | NSD 发现 + TCP 长连接 + 换行 JSON 协议；权限位校验；待确认请求 |
| 行为自适应 | `engine/adaptive/*` | 中位数统计 → 建议卡片 |
| 天气联动 | `engine/weather/*` | Open-Meteo + Room 缓存 + 过期不提前 |
| 统计页 | `ui/stats/*` | 自绘折线（起床趋势）+ 贪睡榜 + 被静音榜 |
| 保活自检 | `work/SelfCheckWorker` | 6h 周期对比 Room 期望与 AlarmManager 实际注册，缺则补排 |
| 数据对外共享 | `provider/AlarmProvider` | 只读 `content://com.course.tongxiao.alarms`（答辩加分项） |

## 3. 构建与运行

```bat
cd D:\Android\tongxiao
gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- 工具链：`D:\Android\sdk`（compileSdk 36）、`D:\Android\jdk-17`、`D:\Android\gradle`（8.14.3，wrapper 走腾讯云镜像）
- 模拟器：`emulator -avd Pixel_7_API_36`（AVD 已创建；无头运行加 `-no-window -no-audio`）
- 单元/集成测试：`gradlew connectedDebugAndroidTest`（需模拟器或真机）

## 4. 测试

- **instrumentation 测试**：110+ 个用例，覆盖数据层、调度、响铃链路、挑战、互助协议、智能引擎、统计、保活自检
- **lint**：0 error，仅剩 2 条已知提示（`allowBackup` 在 `allowBackup=false` 下无意义、应用图标待补）
- 测试策略：纯逻辑（节假日/触发/协议/自适应/统计）用确定性种子与合成数据；涉及系统的（调度/响铃/服务）在模拟器实测并通过 `AlarmManager.getNextAlarmClock()`、`NotificationManager.getActiveNotifications()`、`PendingIntent.FLAG_NO_CREATE` 做"系统真的记住了"级断言

## 5. 交付物与签名

| 产物 | 路径 | 说明 |
|---|---|---|
| Debug APK | `dist/tongxiao-preview.apk` | 自测用，debug 签名 |
| **Release APK** | `dist/tongxiao-release.apk` | **提交/演示用**，正式签名（`CN=Tongxiao`） |
| 签名密钥 | `D:\Android\tongxiao-keys\tongxiao.jks` | **在仓库外**，不进版本库；口令见 build.gradle 的默认值（可 `-P` 覆盖） |

```bat
gradlew assembleRelease
adb install -r dist\tongxiao-release.apk
```

> 重新打包签名：`gradlew assembleRelease -PtongxiaoKeystore=D:/path/x.jks -PtongxiaoStorePassword=***`

## 6. 已知限制（答辩要能解释）

1. **全屏停铃页只在锁屏/熄屏时自动弹出**：Android 10+ 禁止后台直接 `startActivity`，亮屏解锁时走"环形通知 + 停止按钮"。这是平台规范，不是实现缺陷。
2. **厂商 ROM 杀后台**：需手动加白（自启动/电池优化）；已提供引导与 6h 自检兜底。
3. **不精确闹钟兜底**：若用户撤销精确闹钟权限，退化为 `setAndAllowWhileIdle`（可能晚几十秒），保证"宁响勿哑"。
4. **宿舍互助为局域网**：需同一 Wi-Fi/热点；跨网络不可用（设计取舍：零服务器）。
5. **节假日数据**：内置到 2026 年；2027 公告发布后需更新 JSON（设置页预留导入入口）。

## 7. 目录

```
app/src/main/java/com/course/tongxiao/
├── alarm/      调度、响铃服务、停铃页、系统事件、状态机
├── data/       Room 实体/DAO/Repository、SharedPreferences（SettingsStore）
├── engine/     节假日、下次触发、挑战、自适应、天气
├── group/      局域网互助（协议、NSD/TCP、组管理、权限闸门）
├── ui/         列表/编辑/设置/挑战/统计页与 Fragment
├── work/       6h 自检
├── provider/   只读 ContentProvider
docs/           design.md（设计）、plan.md（执行计划）、test-report.md（测试报告）、ppt-outline.md（答辩大纲）、demo-script.md（演示脚本）、wrapup-plan.md（收尾计划）
```
