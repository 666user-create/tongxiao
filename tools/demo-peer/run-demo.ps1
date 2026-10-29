# Runs the two-party group demo against the emulator.
#
# Why this exists: the LAN group needs two devices and the emulator's Wi-Fi is NATed,
# so a second emulator can never discover the first over mDNS. Instead `peer.py`
# speaks the app's real wire protocol through `adb forward` and plays the second
# member (handshake with the real 6-digit code, heartbeats, CMD_MUTE, SET_REQUEST,
# STATS_PUSH) - the app sees a genuine peer, not a mocked UI.
#
# Usage (dry run):   pwsh tools\demo-peer\run-demo.ps1
# Usage (recording): pwsh tools\demo-peer\run-demo.ps1 -Record
param(
    [switch]$Record,
    [string]$Serial = "emulator-5556",
    [string]$Nick = "小李",
    [string]$DevId = "peer-lixiao",
    [int]$ForwardPort = 19000
)

# adb writes progress to stderr; "Stop" would abort on that. Checks are explicit below.
$ErrorActionPreference = "Continue"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tmp = Join-Path $repo ".g5"
if (-not (Test-Path $tmp)) { New-Item -ItemType Directory $tmp | Out-Null }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = "D:\Android\sdk\platform-tools\adb.exe" }
$python = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $python) {
    $python = "C:\Users\$env:USERNAME\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
}
$ctl = Join-Path $tmp "peer_cmd.txt"
$peerLog = Join-Path $tmp "peer.log"

function Dump-Screen([string]$name) {
    & $adb -s $Serial shell uiautomator dump /sdcard/u.xml 2>&1 | Out-Null
    & $adb -s $Serial pull /sdcard/u.xml (Join-Path $tmp "$name.xml") 2>&1 | Out-Null
    return (Get-Content (Join-Path $tmp "$name.xml") -Raw -Encoding UTF8)
}

function Tap-Id([string]$xml, [string]$id) {
    $m = [regex]::Match($xml, 'resource-id="com\.course\.tongxiao:id/' + $id +
        '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb -s $Serial shell input tap $x $y
    return $true
}

function Tap-Text([string]$xml, [string]$text) {
    $m = [regex]::Match($xml, 'text="' + [regex]::Escape($text) +
        '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb -s $Serial shell input tap $x $y
    return $true
}

function Send-Peer([string]$command) {
    [IO.File]::AppendAllText($ctl, "$command`n", [Text.UTF8Encoding]::new($false))
    Start-Sleep -Milliseconds 1500
}

# ---- 1) clean slate ----
& $adb -s $Serial shell pm clear com.course.tongxiao 2>&1 | Out-Null
& $adb -s $Serial shell pm grant com.course.tongxiao android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null
& $adb -s $Serial forward --remove-all 2>&1 | Out-Null
Get-Process | Where-Object { $_.ProcessName -match '^python' } | Stop-Process -Force -ErrorAction SilentlyContinue
& $adb -s $Serial logcat -c
& $adb -s $Serial shell am start -W -n com.course.tongxiao/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 4

# ---- 2) create a group ----
& $adb -s $Serial shell input tap 405 2232    # 互助 tab
Start-Sleep -Seconds 3
$ui = Dump-Screen "demo1"
if (-not (Tap-Text $ui "创建互助组")) { throw "create-group button not found" }
Start-Sleep -Seconds 4
$ui = Dump-Screen "demo2"
$code = [regex]::Match($ui, '组码\s*(\d{6})').Groups[1].Value
if (-not $code) { throw "group code not found on screen" }
Write-Host "group code: $code"

# ---- 3) wire the protocol peer to the app's listening port ----
$ssLine = ((& $adb -s $Serial shell "ss -ltnp 2>/dev/null | grep course.tongxiao") -join '')
$port = [regex]::Match($ssLine, ':(\d+)\s').Groups[1].Value
if (-not $port) { throw "app listening port not found: $ssLine" }
Write-Host "app port: $port"
& $adb -s $Serial forward tcp:$ForwardPort tcp:$port | Out-Null

[IO.File]::WriteAllText($ctl, "", [Text.UTF8Encoding]::new($false))
$peer = Start-Process -FilePath $python -ArgumentList @(
    (Join-Path $PSScriptRoot "peer.py"), "--port", $ForwardPort, "--group", $code,
    "--nick", $Nick, "--dev", $DevId, "--control", $ctl
) -RedirectStandardOutput $peerLog -RedirectStandardError ($peerLog + ".err") -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 3
Write-Host "peer pid: $($peer.Id)"
Get-Content $peerLog -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -First 4

# ---- 4) optional recording ----
$recorder = $null
if ($Record) {
    & $adb -s $Serial shell rm -f /sdcard/group-demo.mp4 2>&1 | Out-Null
    $recorder = Start-Job -ScriptBlock {
        param($adbPath, $serial)
        & $adbPath -s $serial shell "screenrecord --time-limit 120 --bit-rate 4000000 --size 720x1600 /sdcard/group-demo.mp4"
    } -ArgumentList $adb, $Serial
    Start-Sleep -Seconds 2
}

# ---- 5) the demo beats ----
Write-Host "STEP 1: peer shows up in the member list"
$ui = Dump-Screen "demo3"
if (-not ($ui -match [regex]::Escape($Nick))) { throw "peer did not join" }

Write-Host "STEP 2: grant 可代设 to the peer"
Start-Sleep -Seconds 2
if (-not (Tap-Text $ui "可代设")) { Write-Host "  (可代设 chip not found, continuing)" }
Start-Sleep -Seconds 2

Write-Host "STEP 3: the peer's alarm rings, I mute it remotely"
Send-Peer "ringing"
Start-Sleep -Seconds 2
$ui = Dump-Screen "demo4"
if (-not (Tap-Text $ui "静音")) {
    Write-Host "  (mute button not found) page:"
    [regex]::Matches($ui, 'text="([^"]{1,30})"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 14 | ForEach-Object { Write-Host "    $_" }
}
Start-Sleep -Seconds 3

Write-Host "STEP 4: the peer asks me to set an alarm, I accept"
Send-Peer "setreq"
Start-Sleep -Seconds 3
$ui = Dump-Screen "demo5"
if (-not (Tap-Text $ui "同意")) { Write-Host "  (accept button not found)" }
Start-Sleep -Seconds 3

Write-Host "STEP 5: leaderboard shows the peer"
Send-Peer "stats"
Start-Sleep -Seconds 2
& $adb -s $Serial shell input tap 675 2232   # 统计 tab
Start-Sleep -Seconds 3
$ui = Dump-Screen "demo6"
[regex]::Matches($ui, 'text="([^"]{2,32})"') |
    ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_ -match "$Nick|榜单|贪睡|静音" } |
    Select-Object -First 8

# ---- 6) finish ----
if ($recorder) {
    Wait-Job $recorder -Timeout 180 | Out-Null
    Remove-Job $recorder -Force -ErrorAction SilentlyContinue
    $out = Join-Path $repo "dist\demo-group.mp4"
    & $adb -s $Serial pull /sdcard/group-demo.mp4 $out 2>&1 | Out-Null
    Write-Host "video: $out $([math]::Round((Get-Item $out).Length/1MB,2)) MB"
}

Write-Host "STEP 6: leave the group (cleanup)"
& $adb -s $Serial shell input tap 405 2232
Start-Sleep -Seconds 2
$ui = Dump-Screen "demo7"
if (Tap-Text $ui "退出互助组") { Start-Sleep -Seconds 2 }
Send-Peer "quit"
Start-Sleep -Seconds 2
Get-Content $peerLog -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 6
Write-Host "done"
