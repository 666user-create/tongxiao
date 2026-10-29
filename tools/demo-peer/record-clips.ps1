# Records the feature demo as short, individually re-recordable clips.
#
# Why per scene: one continuous 3-minute take has to be perfect in a single pass
# (any mis-tap wastes the whole recording). Short clips can be re-recorded on their
# own and sequenced in the slide deck, which is also what a dubbed video needs.
#
# Usage: .\record-clips.ps1 -Only S1     (omit -Only to record every scene)
param(
    [string]$Serial = "emulator-5556",
    [string[]]$Only = @()
)

$ErrorActionPreference = "Continue"
$repo = "D:\Android\tongxiao"
$adb = "D:\Android\sdk\platform-tools\adb.exe"
$py = "C:\Users\$env:USERNAME\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
$logDir = Join-Path $repo ".g5"
$outDir = Join-Path $repo "dist\demo-clips"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$ctl = Join-Path $logDir "peer_cmd.txt"

function Dump([string]$name) {
    & $adb -s $Serial shell uiautomator dump /sdcard/u.xml 2>&1 | Out-Null
    & $adb -s $Serial pull /sdcard/u.xml (Join-Path $logDir "$name.xml") 2>&1 | Out-Null
    return (Get-Content (Join-Path $logDir "$name.xml") -Raw -Encoding UTF8)
}

function Coord([string]$xml, [string]$id) {
    $m = [regex]::Match($xml, 'resource-id="com\.course\.tongxiao:id/' + $id +
        '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $null }
    return @{ x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
              y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2) }
}

function CoordText([string]$xml, [string]$text) {
    $m = [regex]::Match($xml, 'text="' + [regex]::Escape($text) +
        '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $null }
    return @{ x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
              y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2) }
}

function CoordPartial([string]$xml, [string]$text) {
    $m = [regex]::Match($xml, 'text="[^"]*' + [regex]::Escape($text) + '[^"]*"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $null }
    return @{ x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
              y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2) }
}

function Tap([string]$id, [string]$dumpName = "tmp") {
    $ui = Dump $dumpName
    $c = Coord $ui $id
    if (-not $c) { $c = CoordText $ui $id }
    if ($c) { & $adb -s $Serial shell input tap $c.x $c.y; return $true }
    Write-Host "  ! tap target not found: $id"
    return $false
}

function TapAt([int]$x, [int]$y) { & $adb -s $Serial shell input tap $x $y }
function Sleep([double]$s) { Start-Sleep -Milliseconds ([int]($s * 1000)) }
function SwipeUp() { & $adb -s $Serial shell input swipe 540 1700 540 900 180 }

function StartRec([string]$name, [int]$limit = 60) {
    & $adb -s $Serial shell rm -f "/sdcard/$name.mp4" 2>&1 | Out-Null
    $script:rec = Start-Job -ScriptBlock {
        param($a, $s, $n, $limit)
        & $a -s $s shell "screenrecord --time-limit $limit --bit-rate 6000000 --size 720x1600 /sdcard/$n.mp4"
    } -ArgumentList $adb, $Serial, $name, $limit
    Sleep 1.2
    # A clean status bar avoids a half-drawn clock in the first frame.
    & $adb -s $Serial shell settings put global auto_time 0 2>&1 | Out-Null
    # screenrecord captures nothing while the display is off; keep it awake.
    & $adb -s $Serial shell svc power stayon true 2>&1 | Out-Null
    & $adb -s $Serial shell settings put system screen_off_timeout 600000 2>&1 | Out-Null
    & $adb -s $Serial shell input keyevent 224 2>&1 | Out-Null
    & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
}

function StopRec([string]$name) {
    Sleep 1.5
    & $adb -s $Serial shell pkill -INT screenrecord 2>&1 | Out-Null
    Wait-Job $script:rec -Timeout 90 | Out-Null
    Remove-Job $script:rec -Force -ErrorAction SilentlyContinue
    $dest = Join-Path $outDir "$name.mp4"
    & $adb -s $Serial pull "/sdcard/$name.mp4" $dest 2>&1 | Out-Null
    if (Test-Path $dest) {
        Write-Host ("  saved {0} ({1:N2} MB)" -f (Split-Path -Leaf $dest), ((Get-Item $dest).Length / 1MB))
    } else {
        Write-Host "  ! recording missing: $name"
    }
}

function Want([string]$scene) {
    return ($Only.Count -eq 0) -or ($Only -contains $scene)
}

function OpenApp() {
    & $adb -s $Serial shell am force-stop com.course.tongxiao 2>&1 | Out-Null
    Sleep 0.5
    & $adb -s $Serial shell am start -W -n com.course.tongxiao/.MainActivity 2>&1 | Out-Null
    Sleep 2.5
}

# ---------------------------------------------------------------- S1 新建闹钟
if (Want "S1") {
    Write-Host "S1 新建闹钟（滚动式时间 + 智能工作日 + 选择题 + 保存）"
    & $adb -s $Serial shell "date 102907282026.00" 2>&1 | Out-Null
    OpenApp
    StartRec "01_create_alarm"
    Sleep 1
    # 新建
    & $adb -s $Serial shell input tap 954 1932
    Sleep 2.5
    # 展开滚动式时间选择，停 2 秒再收起（证明是滚轮而不是输入框）
    & $adb -s $Serial shell input tap 540 463
    Sleep 3
    & $adb -s $Serial shell input keyevent 4
    Sleep 1.5
    # 重复：智能工作日（默认已是）+ 滚到挑战区选"选择题"
    SwipeUp; Sleep 0.5; SwipeUp; Sleep 0.5; SwipeUp
    Sleep 1
    & $adb -s $Serial shell input tap 415 1474
    Sleep 1.5
    # 保存
    & $adb -s $Serial shell input tap 540 2232
    Sleep 3
    StopRec "01_create_alarm"
}

# ------------------------------------------------------- S2 铃声 + 振动强度
if (Want "S2") {
    Write-Host "S2 铃声（系统选择器）+ 振动强度（点强试振）"
    StartRec "02_ringtone_vibration"
    $ui = Dump "s2a"
    $card = Coord $ui "alarm_card"
    if ($card) { TapAt $card.x $card.y }
    Sleep 2.5
    SwipeUp; Sleep 0.6; SwipeUp
    Sleep 1
    # 铃声行 → 打开系统选择器
    & $adb -s $Serial shell input tap 540 1146
    Sleep 4
    & $adb -s $Serial shell input keyevent 4
    Sleep 2
    # 振动强度：轻/中/强（点"强"会试振）
    $ui = Dump "s2b"
    $strong = Coord $ui "vibrate_strong"
    if ($strong) { TapAt $strong.x $strong.y }
    Sleep 2.5
    & $adb -s $Serial shell input keyevent 4
    Sleep 1.5
    StopRec "02_ringtone_vibration"
}

# --------------------------------------------------- S3 真响铃 + 停铃挑战
if (Want "S3") {
    Write-Host "S3 真响铃 → 点开停铃页 → 挑战答题（答错一次再答对）"

    # 不调整系统时钟（模拟器会被宿主机同步回去）：写入一条"2 分钟后"的闹钟，
    # 再走编辑页保存让 App 正常排程，然后按剩余秒数等待响铃。
    OpenApp
    $hm = ((& $adb -s $Serial shell "date +%H:%M") -join '').Trim()
    $parts = $hm -split ':'
    $hh = [int]$parts[0]; $mm = [int]$parts[1] + 2
    if ($mm -ge 60) { $mm = $mm - 60; $hh = ($hh + 1) % 24 }
    $quiz = '{"mode":"QUIZ","questionCount":2,"types":["ARITHMETIC","GENERAL"],"wrongPolicy":"RETRY","delaySeconds":5,"shakeCount":5,"questionSource":"BUILT_IN"}'
    $seedSql = "UPDATE alarms SET enabled=0;" +
        "DELETE FROM alarms WHERE uid LIKE 'demo-%';" +
        "INSERT INTO alarms (uid,label,hour,minute,dayMask,repeatMode,skipHolidays,vibrate,vibrateStrength,snoozeMin,challengeOverrideJson,advanceEnabled,advanceMaxMin,enabled,sharedToGroup) " +
        "VALUES ('demo-$hh$mm','演示闹钟',$hh,$mm,0,'DAILY',1,1,1,10,'$quiz',0,0,1,1);"
    [IO.File]::WriteAllText((Join-Path $logDir "s3seed.sql"), $seedSql, [Text.UTF8Encoding]::new($false))
    & $adb -s $Serial push (Join-Path $logDir "s3seed.sql") /data/local/tmp/s3seed.sql 2>&1 | Out-Null
    & $adb -s $Serial shell "chmod 644 /data/local/tmp/s3seed.sql"
    & $adb -s $Serial shell "run-as com.course.tongxiao sqlite3 databases/tongxiao.db < /data/local/tmp/s3seed.sql" 2>&1 | Out-Null
    OpenApp
    $ui = Dump "s3pre"
    $newRow = CoordText $ui "演示闹钟"
    if ($newRow) { TapAt $newRow.x $newRow.y }
    Sleep 2.5
    & $adb -s $Serial shell input tap 540 2232
    Sleep 3

    $nowHm = ((& $adb -s $Serial shell "date +%H:%M:%S") -join '').Trim()
    $np = $nowHm -split ':'
    $nowSec = [int]$np[0] * 3600 + [int]$np[1] * 60 + [int]$np[2]
    $fireSec = $hh * 3600 + $mm * 60
    $delta = $fireSec - $nowSec
    if ($delta -lt 0) { $delta += 86400 }
    Write-Host ("  target $hh`:$mm  now $nowHm  → 响铃还有 $delta 秒")
    $reg = ((& $adb -s $Serial shell "dumpsys alarm | grep -A2 'com.course.tongxiao.action.FIRE_ALARM' | grep origWhen") -join ' ')
    Write-Host ("  registration: " + (($reg -replace '\s+', ' ').Trim()))

    & $adb -s $Serial shell input keyevent 224 2>&1 | Out-Null
    & $adb -s $Serial shell wm dismiss-keyguard 2>&1 | Out-Null
    & $adb -s $Serial shell am start -n com.course.tongxiao/.MainActivity 2>&1 | Out-Null
    Sleep 1

    StartRec "03_ring_challenge" 130
    # 提前 8 秒开始"等"，让画面里能看到闹钟到点弹出
    $wait = [Math]::Max(5, $delta - 8)
    Sleep $wait
    $ui = Dump "s3a"
    Write-Host ("  ring panel: " + ([bool]($ui -match "停止")))
    $panel = CoordPartial $ui "点按打开停铃页"
    if ($panel) { TapAt $panel.x $panel.y; Sleep 2.5 }
    $ui = Dump "s3b"
    $stop = CoordText $ui "停止"
    if ($stop) { TapAt $stop.x $stop.y }
    Sleep 3
    $ui = Dump "s3c"
    Write-Host ("  challenge page: " + ([bool]($ui -match "答对才能停铃")))
    $optMatches = [regex]::Matches($ui, 'text="([^"]{1,16})"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    $answers = @()
    foreach ($m in $optMatches) {
        $label = $m.Groups[1].Value
        if ($label -notmatch '题|答|停止|紧急|第' -and $label.Trim().Length -gt 0) {
            $answers += @{ x = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
                           y = [int](([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2) }
        }
    }
    $answers = $answers | Select-Object -Last 4
    if ($answers.Count -ge 4) {
        # 先故意答错一次（画面里会出现"答错了"反馈）
        TapAt $answers[0].x $answers[0].y
        Sleep 3.5
        # 再逐个试，直到停铃页消失（说明某一项答对、闹钟停了）
        for ($round = 0; $round -lt 10; $round++) {
            TapAt $answers[$round % 4].x $answers[$round % 4].y
            Sleep 2.8
            $ui = Dump "s3d"
            if ($ui -notmatch "停止") { break }
        }
        Sleep 2
    }
    Sleep 2.5
    StopRec "03_ring_challenge"
}
# ------------------------------------------------------------ S4 自定义题目
if (Want "S4") {
    Write-Host "S4 自定义题目（出题 → 保存 → 列表）"
    OpenApp
    & $adb -s $Serial shell input tap 945 2232
    Sleep 2
    for ($i = 0; $i -lt 4; $i++) { SwipeUp; Sleep 0.3 }
    Sleep 0.8
    StartRec "04_custom_question"
    $ui = Dump "s4a"
    $entry = Coord $ui "settings_custom_questions"
    if ($entry) { TapAt $entry.x $entry.y }
    Sleep 2
    Tap "custom_questions_add" "s4b" | Out-Null
    Sleep 2
    $ui = Dump "s4c"
    $text = Coord $ui "custom_question_text"
    if ($text) {
        TapAt $text.x $text.y
        Sleep 0.6
        & $adb -s $Serial shell input text "sun%srive%sfrom"
        Sleep 0.8
        & $adb -s $Serial shell input keyevent 4
        Sleep 1
    }
    $ui = Dump "s4d"
    $opts = @("A", "B", "C", "D")
    $vals = @("east", "west", "north", "south")
    for ($i = 1; $i -le 4; $i++) {
        $ui = Dump "s4e$i"
        $field = Coord $ui "custom_option_$i"
        if ($field) {
            TapAt $field.x $field.y
            Sleep 0.5
            & $adb -s $Serial shell input text $vals[$i - 1]
            Sleep 0.5
            & $adb -s $Serial shell input keyevent 4
            Sleep 0.5
        }
    }
    $ui = Dump "s4f"
    $correct = Coord $ui "custom_correct_1"
    if ($correct) { TapAt $correct.x $correct.y }   # A = east 是正确答案
    Sleep 1
    $ok = CoordText $ui "确定"
    if ($ok) { TapAt $ok.x $ok.y }
    Sleep 2.5
    StopRec "04_custom_question"
}

# -------------------------------------------------------- S5 自适应贪睡建议
if (Want "S5") {
    Write-Host "S5 行为建议（最近 6 天都在 07:52 才停铃 → 建议改到 07:50 → 采纳）"
    OpenApp
    StartRec "05_adaptive_suggestion"
    $ui = Dump "s5a"
    $card = CoordText $ui "背单词"
    if (-not $card) { $card = Coord $ui "alarm_card" }
    if ($card) { TapAt $card.x ([int]$card.y - 40) }
    Sleep 3
    $ui = Dump "s5b"
    Write-Host ("  suggestion card: " + ([bool]($ui -match "行为建议")))
    $accept = CoordText $ui "采纳"
    if ($accept) { TapAt $accept.x $accept.y }
    Sleep 2
    & $adb -s $Serial shell input tap 540 2232   # 保存
    Sleep 3
    StopRec "05_adaptive_suggestion"
}

# ------------------------------------------------------------- S6 双人互助
if (Want "S6") {
    Write-Host "S6 双人互助（建组 → 入组 → 远程静音 → 代设确认）"
    Get-Process | Where-Object { $_.ProcessName -match '^python' } | Stop-Process -Force -ErrorAction SilentlyContinue
    & $adb -s $Serial forward --remove-all 2>&1 | Out-Null
    OpenApp
    & $adb -s $Serial shell input tap 405 2232
    Sleep 2.5
    StartRec "06_group"
    $ui = Dump "s6a"
    $create = CoordText $ui "创建互助组"
    if ($create) { TapAt $create.x $create.y }
    Sleep 4
    # 组码直接从界面读（App 建组后就显示在"组码 XXXXXX"），
    # 避免为了一个 6 位数字去拼 SQL。
    $uiCreated = Dump "s6code"
    $code = [regex]::Match($uiCreated, '组码\s*(\d{6})').Groups[1].Value
    Write-Host ("  group code from screen: " + $code)
    $ss = ((& $adb -s $Serial shell "ss -ltnp 2>/dev/null | grep course.tongxiao") -join '')
    $port = [regex]::Match($ss, ':(\d+)\s').Groups[1].Value
    Write-Host "  code=$code port=$port"
    if ($code -and $port) {
        & $adb -s $Serial forward tcp:19000 tcp:$port 2>&1 | Out-Null
        [IO.File]::WriteAllText($ctl, "", [Text.UTF8Encoding]::new($false))
        $peer = Start-Process -FilePath $py -ArgumentList @(
            (Join-Path $repo "tools\demo-peer\peer.py"), "--port", "19000", "--group", $code,
            "--nick", "小李", "--dev", "peer-lixiao", "--control", $ctl
        ) -RedirectStandardOutput (Join-Path $logDir "s6peer.log") `
          -RedirectStandardError (Join-Path $logDir "s6peer.err") -PassThru -WindowStyle Hidden
        Sleep 5
        $ui = Dump "s6b"
        $grant = CoordText $ui "可代设"
        if ($grant) { TapAt $grant.x $grant.y }
        Sleep 2
        [IO.File]::AppendAllText($ctl, "ringing`n", [Text.UTF8Encoding]::new($false))
        Sleep 5
        $ui = Dump "s6c"
        $mute = CoordText $ui "静音"
        if ($mute) { TapAt $mute.x $mute.y }
        Sleep 4
        [IO.File]::AppendAllText($ctl, "setreq`n", [Text.UTF8Encoding]::new($false))
        Sleep 5
        $ui = Dump "s6d"
        $accept = CoordText $ui "同意"
        if ($accept) { TapAt $accept.x $accept.y }
        Sleep 4
    }
    StopRec "06_group"
}

# ------------------------------------------------------------- S7 统计页
if (Want "S7") {
    Write-Host "S7 统计页（趋势 / 贪睡排行 / 被静音 / 互助榜单）"
    StartRec "07_stats"
    & $adb -s $Serial shell input tap 675 2232
    Sleep 4
    SwipeUp
    Sleep 2
    StopRec "07_stats"
}
