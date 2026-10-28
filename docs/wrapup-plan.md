# 同晓 · 收尾计划（v1 完成版）

日期：2026-10-28 · 当前基线：`abe3b52`（90/90 测试绿，M1–M3 完成）

## 0. 目标与验收定义

把 M4/M5/M6 全部实现到"可演示 + 可交付"：

- **功能完整**：宿舍互助（局域网）、行为自适应建议、天气提前、统计页、保活加固、ContentProvider
- **质量门禁不变**：每步 G1 构建（含 lint）零新增警告、G2 测试全绿、G4 独立评审、文档与代码同 commit
- **交付物**：APK、测试报告、README、答辩 PPT 大纲、演示脚本

## 1. 剩余任务分解（含归属）

| 编号 | 任务 | 关键产出 | 负责人 | 依赖 |
|---|---|---|---|---|
| W1 | 协议编解码 | `group/Protocol.java`、`Message.java` + 单测（坏行/粘包/未知类型） | 网络组 | — |
| W2 | NSD + TCP 通道 | `group/PeerLink.java`、`NsdDiscovery.java`、`GroupManager.java` + 回环测试 | 网络组 | W1 |
| W3 | 权限与待确认请求 | `grantedMask` 校验、接收端钳制、`pending_requests` 生命周期、2h 过期 | 网络组 | W2 |
| W4 | 静音/代设链路 | `CMD_MUTE`/`SET_REQUEST` 与 `RingActions`、`AlarmScheduler` 接线 | Lead 集成 | W3 |
| W5 | 宿舍页 UI | `group` tab + 成员列表 + 操作流 + 待确认卡 | Lead | W4 |
| W6 | 自适应建议 | `engine/adaptive/AdaptiveEngine.java` + 单测（中位数/阈值/冷启动） | 智能组 | — |
| W7 | 天气联动 | `engine/weather/WeatherClient.java` + `weather_cache` 读写 + 单测（mock JSON/过期降级） | 智能组 | — |
| W8 | 统计页 | 自绘折线（起床趋势）+ 贪睡榜 + 被静音榜 | 统计组 | W6/W7 数据 |
| W9 | 保活加固 | WorkManager 6h 自检补排 + 厂商白名单引导页 + `canUseFullScreenIntent` 检查 | 统计组 | — |
| W10 | ContentProvider | 只读 `content://com.course.tongxiao.alarms` | 统计组 | — |
| W11 | 文档与答辩材料 | 测试报告、README（含架构图/考点对照）、PPT 大纲、演示脚本 | Lead | 全部 |
| W12 | 端到端验收 | 双模拟器互助演示 + 3 晚响铃 + 测试矩阵 | Lead | 全部 |
| W13 | 模拟器实测并自修 | 12 项流程实测 + 缺陷修复 + 回归测试 | QA 组 | 全部 |

### 执行状态（截至 2026-10-28）

| 编号 | 状态 | 备注 |
|---|---|---|
| W1–W10 | ✅ 完成 | 328 条 instrumentation 测试；两轮独立评审（§5.1/§5.2 of test-report）抓出 3 个 BLOCKER / 20+ MAJOR 并全部修复 |
| W11 | ✅ 完成 | `README.md`、`test-report.md`、`ppt-outline.md`、`demo-script.md`、`acceptance-checklist.md`；文档承诺已与实现逐条对齐（未实现的进"展望"） |
| W12 | ⏳ 部分 | 单机流程已在模拟器实测；**双机同热点、连续 3 晚真实数据**需真机，属手工项 |
| W13 | ✅ 完成 | 模拟器 12 项实测，抓出 7 个真缺陷并修复（含 1 个 P1 漏响），另加 2 处 Lead 补修；见 test-report §5.3 |


## 2. 并行策略（文件归属，避免写冲突）

- **Lead 独占**：`AndroidManifest.xml`、`MainActivity`、`activity_main.xml`、`menu_bottom_nav.xml`、`docs/*`、`dist/*`
- **网络组**：`group/**`、`res/values/strings_group.xml`、`res/layout/fragment_group.xml`、`androidTest/**/group/**`
- **智能组**：`engine/adaptive/**`、`engine/weather/**`、`res/values/strings_smart.xml`、`androidTest/**/engine/adaptive|weather/**`
- **统计组**：`ui/stats/**`、`provider/**`、`work/**`、`res/values/strings_stats.xml`、`res/layout/fragment_stats.xml`、`androidTest/**/stats|work/**`
- 需要修改共享文件时：**不要直接改**，在完成报告里列出"需要 Lead 做的接线"，由 Lead 统一改并跑测试

## 3. 每项任务的完成标准

1. `gradlew build` 通过，lint 无新增警告
2. 新增测试全部通过（在模拟器实跑），且能抓住至少一个真实反例（假绿视为未完成）
3. 代码注释用英文、标识符清晰、无投机代码（karpathy 规则）
4. 报告：改了哪些文件、如何验证、需要 Lead 接哪些线、已知限制

## 4. 收尾阶段（M6）

1. 测试矩阵执行 → `docs/test-report.md`
2. 双机互助演示脚本（含失败降级路径）
3. README + 答辩 PPT 大纲（讲义点对照附录 A）
4. 打 release APK（debug 签名说明）+ 最终演示包

## 5. 风险与应对

| 风险 | 应对 |
|---|---|
| 双设备 NSD 联调不稳定 | 回环（127.0.0.1）单测覆盖协议与权限；真机用同一热点；演示前预跑一遍 |
| 天气 API 不可用 | 缓存过期即"不提前"；答辩用离线 fixture 演示 |
| 厂商 ROM 杀后台导致不响 | 白名单引导 + 6h 自检 + 演示机预先加白 |
| 并行开发写冲突 | 上面的文件归属表；共享文件只由 Lead 改 |
| 时间不足 | 按 W1→W5 / W6→W9 两条线推进，UI 最后接入；宁可砍统计美化也不砍互助主链路 |
