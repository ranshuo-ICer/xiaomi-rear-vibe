# scripts/rear-switch.ps1 - put ANY app on the Xiaomi rear screen (adb only).
#
# The Xiaomi rear display (Mi 17 Pro / Pro Max) REFUSES third-party activity
# starts:  am start --display 1 -n <3rd-party>   ->  Error code 102
# (MIUI ActivityStarter logs "should not allow app ... show on rear display").
# The stack-move path is NOT checked, and that is the whole trick:
#
#   1. wake  : input -d <rear> keyevent KEYCODE_WAKEUP   (shell cannot send
#              miui.intent.action.SUB_SCREEN_ON - permission denied)
#   2. base  : make sure the rear launcher (SubScreenLauncher) is alive;
#              without a home root task nothing on the display ever resumes
#   3. start : launch the app normally on display 0        (never blocked)
#   4. move  : am display move-stack <rootTaskId> <rear>   (bypasses 102)
#   5. vis   : if it lands visible=false, round-trip move 0 -> 1 to re-insert
#              on top of the rear home task
#   6. shot  : screencap -d <SF display id> (NOT the logical id!)
#
# Usage:
#   pwsh -File rear-switch.ps1 -Package com.dsh.rearvibe -Activity .MainActivity
#   pwsh -File rear-switch.ps1 -Restore                # bring rear home back
#   pwsh -File rear-switch.ps1 -Adb adb -Serial emulator-5554 -RearDisplay 1
param(
    [string]$Adb = "adb",
    [string]$Serial,
    [string]$Package = "com.dsh.rearvibe",
    [string]$Activity = ".MainActivity",
    [int]$RearDisplay = 1,
    [string]$RearSfId,                 # auto-detected when empty
    [string]$RearLauncher = "com.xiaomi.subscreencenter/.SubScreenLauncher",
    [switch]$Restore,
    [switch]$NoCapture
)

function Invoke-Adb([string]$ShellCmd) {
    (& $Adb -s $script:Serial shell $ShellCmd 2>&1) | ForEach-Object { "$_" }
}

# ---- 0. device discovery ----
if (-not $Serial) {
    $found = & $Adb devices | Where-Object { $_ -match "`tdevice$" } |
        ForEach-Object { ($_ -split "`t")[0] }
    if ($found.Count -eq 1) { $Serial = $found[0] }
    elseif ($found.Count -eq 0) {
        & $Adb connect 127.0.0.1:5555 2>$null | Out-Null
        throw "no adb device - enable wireless debugging / plug in a phone, then re-run"
    }
    else { throw "multiple devices, pass -Serial explicitly: $($found -join ', ')" }
}

# resolve the SF display id of the rear screen (uniqueId local:<sfId>)
if (-not $RearSfId) {
    $info = Invoke-Adb "dumpsys display"
    $line = ($info | Where-Object { $_ -match "displayId $RearDisplay," } |
             Where-Object { $_ -match 'uniqueId "local:' } | Select-Object -First 1)
    if ($line -match 'uniqueId "local:(\d+)"') { $RearSfId = $Matches[1] }
    else { throw "cannot auto-detect SF display id for display $RearDisplay - pass -RearSfId" }
}

function Wake-Rear {
    Invoke-Adb "input -d $script:RearDisplay keyevent KEYCODE_WAKEUP" | Out-Null
    Invoke-Adb "dumpsys power setWakefulness 1" | Out-Null
    Start-Sleep -Milliseconds 700
}

function Get-TaskLine([string]$pkg) {
    (Invoke-Adb "am stack list") |
        Select-String "taskId=\d+: $([regex]::Escape($pkg))/" | Select-Object -First 1
}

if ($Restore) {
    Write-Host "[restore] wake rear + start launcher on display $RearDisplay"
    Wake-Rear
    Invoke-Adb "am start --display $RearDisplay -n $RearLauncher" | Out-Null
    Write-Host "[restore] done"
    exit 0
}

# ---- 1. wake + keep the rear home alive as a base layer ----
Write-Host "[1/5] wake rear display $RearDisplay (SF id $RearSfId)"
Wake-Rear
$launcherAlive = (Invoke-Adb "ps -A | grep com.xiaomi.subscreencenter") -match "subscreencenter"
if (-not $launcherAlive) {
    Write-Host "      rear launcher dead - restarting it (base layer)"
    Invoke-Adb "am start --display $RearDisplay -n $RearLauncher" | Out-Null
    Start-Sleep -Milliseconds 900
}

# ---- 2. launch on display 0 (normal path) ----
$comp = if ($Activity -match "/") { $Activity } else { "$Package/$Activity" }
Write-Host "[2/5] launch $comp on display 0"
Invoke-Adb "am start -W -n $comp" | Out-Null
Start-Sleep -Milliseconds 1000

# ---- 3. locate its root task ----
Write-Host "[3/5] locate root task for $Package"
$lines = Invoke-Adb "am stack list"
$rootId = $null; $currentRoot = $null
foreach ($l in $lines) {
    if ($l -match "RootTask id=(\d+)") { $currentRoot = $Matches[1] }
    if ($l -match "taskId=\d+: $([regex]::Escape($Package))/") { $rootId = $currentRoot }
}
if (-not $rootId) { Write-Error "root task for $Package not found"; exit 1 }
Write-Host "      root task id = $rootId"

# ---- 4. THE bypass + visibility fallback ----
Write-Host "[4/5] am display move-stack $rootId $RearDisplay   (bypasses MIUI error 102)"
Invoke-Adb "am display move-stack $rootId $RearDisplay" | Out-Null
Start-Sleep -Milliseconds 1200
$line = Get-TaskLine $Package
if ($line -notmatch "visible=true") {
    Write-Host "      visible=false -> round-trip 0 -> 1 to re-insert on top"
    Invoke-Adb "am display move-stack $rootId 0" | Out-Null
    Start-Sleep -Milliseconds 700
    Invoke-Adb "am display move-stack $rootId $RearDisplay" | Out-Null
    Start-Sleep -Milliseconds 1200
    $line = Get-TaskLine $Package
}
$vis = if ("$line" -match "visible=true") { "visible=true" } else { "visible=false (!)" }
Write-Host "      on display $RearDisplay, $vis"

if (-not $NoCapture) {
    # ---- 5. wake (rear sleeps in ~10s) + screenshot ----
    Write-Host "[5/5] wake + capture rear screen"
    Wake-Rear
    $shot = Join-Path (Get-Location) ("rear-{0}-{1}.png" -f $Package.Split('.')[-1], (Get-Date -Format "HHmmss"))
    cmd /c "`"$Adb`" -s $Serial exec-out screencap -d $RearSfId -p > `"$shot`" 2>nul"
    if ((Test-Path $shot) -and ((Get-Item $shot).Length -gt 4KB)) {
        Write-Host "      shot: $shot ($([math]::Round((Get-Item $shot).Length/1KB)) KB)"
    } else {
        Write-Warning "capture looks empty (display asleep?) - wake and re-run"
    }
}

Write-Host "done. restore with: pwsh -File `"$PSCommandPath`" -Restore"
