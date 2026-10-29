# 双人互助演示工具（demo-peer）

课程演示需要展示"两台设备在同一个互助组里"的功能：组员互相看见时刻表、远程静音、代设闹钟、
互助榜单。但**两个 Android 模拟器之间无法互相发现**——每个模拟器都在自己的 NAT 网段里，
mDNS 广播（`_tongxiao._tcp`）过不去，这是模拟器的已知限制，不是 App 的问题。

所以这里用另一条同样"真实"的路：**在宿主机上运行一个真正的协议对端**，用 `adb forward`
接到 App 的监听端口。它说的是 App 自己的线上协议（换行分隔 JSON）：

| 步骤 | 对端发出 | App 侧表现 |
|---|---|---|
| 握手 | `HELLO {dev,nick,group}` | 用组码校验后把对端登记为组员（与真机完全同一段代码） |
| 分享时刻表 | `SYNC_PUSH {alarms[]}` | 互助页显示对端的闹钟，`ringing:true` 时出现"静音"按钮 |
| 远程静音 | 收到 `CMD_MUTE` 后回 `CMD_ACK{ok,what:"MUTE:<uid>"}` | Toast「已静音组员的闹钟」+ 操作记录留痕 |
| 代设闹钟 | `SET_REQUEST {reqId,kind:"ADD_ALARM",payload{...}}` | "待我确认"出现，点同意后**真的创建闹钟**并回 `SET_RESPONSE` |
| 榜单 | `STATS_PUSH {payload{...}}` | 统计页"互助榜单"出现该组员的起床数据 |
| 心跳 | 每 20s `PING` | 保持在线状态（App 生产配置是 60s 心跳 + 30s 宽限） |

> 因为走的是同一套协议与同一段接收逻辑，App 并不知道对面是手机还是脚本——
> 权限位、`clamped()` 过滤、重复请求去重、ACK 归属（`MUTE:<uid>`）都真的在执行。

## 用法

前置：模拟器已启动、App 已创建互助组并停留在互助页。

```powershell
# 1) 找到 App 的监听端口（PeerServer 用 0 让系统分配）
adb shell "ss -ltnp | grep course.tongxiao"        # 例：*:43101
# 2) 把宿主机端口接到它
adb forward tcp:19000 tcp:43101
# 3) 起对端（组码换成屏幕上的 6 位数）
python tools\demo-peer\peer.py --port 19000 --group 592622 --nick 小李 `
       --control .g5\peer_cmd.txt
```

然后在控制文件里追加命令（一行一条，脚本每 100ms 轮询）：

```
ringing     # 让"小李"的闹钟进入响铃状态
setreq      # 让"小李"发起一个代设闹钟的申请
stats       # 让"小李"推送自己的起床数据（榜单）
calm        # 取消响铃状态
quit        # 断开
```

一条命令的写法（PowerShell，注意用无 BOM 的 UTF-8 写入）：

```powershell
[IO.File]::AppendAllText("$pwd\.g5\peer_cmd.txt", "ringing`n", [Text.UTF8Encoding]::new($false))
```

`run-demo.ps1` 是自动化版本（建组 → 起对端 → 静音 → 代设 → 榜单 → 退出），
加 `-Record` 会同时用 `adb shell screenrecord` 录屏到 `dist/demo-group.mp4`。

## 在真机上做同样的演示

这套脚本也能给老师演示"两台真手机"的效果，只是把 `adb forward` 换成局域网直连：

1. 手机与笔记本连同一个 Wi-Fi；
2. `peer.py` 改成直连手机的 IP + NSD 报告的端口（或让脚本侧也做 mDNS 发现）；
3. 手机上的 App 用同一组码建组，笔记本上的对端用该组码 `HELLO` 即可入组。

更简单的做法是直接装两个手机：一台建组拿到 6 位组码，另一台输入组码加入——
这也是设计文档 §4 的原始场景。

## 已知注意点

- **同一 `deviceId` 的旧链路会被拒绝**（防止反复重连把成员表刷爆）：重跑演示时要么
  `pm clear`，要么给对端换一个 `--dev` 值。
- 控制文件**不能带 BOM**，否则第一行命令会带 `\ufeff`（脚本已做容错，但写入侧也应避免）。
- 对端脚本只用于演示与验收，不参与正式构建（`tools/` 不在 `app/` 源码集内）。
