param([string]$Serial = '192.168.0.153:5555', [switch]$PhysicalRemote)
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\akomh\.t3\projects\tv-fix'
$evidence = Join-Path $root ('aura-team\device\evidence\homekey-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $evidence | Out-Null
$before = ((& adb -s $Serial shell settings get secure enabled_accessibility_services) -join '').Trim()
$enabledBefore = ((& adb -s $Serial shell settings get secure accessibility_enabled) -join '').Trim()
$before | Set-Content "$evidence\enabled_accessibility_services-before.txt"
$enabledBefore | Set-Content "$evidence\accessibility_enabled-before.txt"
& adb -s $Serial shell dumpsys accessibility | Set-Content "$evidence\accessibility-before.txt"
$component = 'tv.aura.probe.homekey/.HomeKeyProbeService'
$services = @($before -split ':' | Where-Object { $_ -and $_ -ne 'null' })
$appended = (@($services | Where-Object { $_ -ne $component }) + $component) -join ':'
$logger = $null
function Mark([string]$Text) {
    "$((Get-Date).ToString('o')) $Text" | Add-Content "$evidence\steps.txt"
    & adb -s $Serial shell log -t AuraProbe "HOST_MARK_$Text"
    & adb -s $Serial shell cat /proc/uptime | Add-Content "$evidence\steps.txt"
}
function Capture([string]$Name) {
    & adb -s $Serial shell dumpsys activity activities | Set-Content "$evidence\$Name-activities.txt"
    & adb -s $Serial shell dumpsys window windows | Set-Content "$evidence\$Name-windows.txt"
    & adb -s $Serial shell screencap -p "/sdcard/$Name.png"
    & adb -s $Serial pull "/sdcard/$Name.png" "$evidence\$Name.png"
    & adb -s $Serial shell rm "/sdcard/$Name.png"
}
try {
    & adb -s $Serial install -r "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Probe APK install failed' }
    $logger = Start-Process -WindowStyle Hidden -FilePath (Get-Command adb).Source -ArgumentList @('-s', $Serial, 'logcat', '-v', 'threadtime', '-s', 'AuraProbe', 'ActivityManager', 'ActivityTaskManager') -PassThru -RedirectStandardOutput "$evidence\logcat.txt" -RedirectStandardError "$evidence\logcat-errors.txt"
    & adb -s $Serial shell settings put secure enabled_accessibility_services $appended
    & adb -s $Serial shell settings put secure accessibility_enabled 1
    Start-Sleep -Seconds 3
    & adb -s $Serial shell dumpsys accessibility | Set-Content "$evidence\accessibility-probe.txt"
    & adb -s $Serial shell am start -W -n tv.aura.probe.homekey/.ProbeActivity
    Start-Sleep -Seconds 1
    if ($PhysicalRemote) {
        # No injected input in this mode. Operator coordinates real remote with orchestrator.
        & adb -s $Serial shell am start -W -n com.spotify.tv.android/.SpotifyTVActivity
        Read-Host 'Tell orchestrator READY. Press Enter only when user is ready for countdown'
        Mark 'PHYSICAL_COUNTDOWN_3S'
        Write-Host 'Starting in 3 seconds: T+0 MENU; T+5 BACK; T+10 short HOME; T+20 hold HOME 2 seconds'
        Start-Sleep -Seconds 3
        $timeline = [Diagnostics.Stopwatch]::StartNew()
        foreach ($step in @(@(0,'MENU'),@(5,'BACK'),@(10,'SHORT_HOME'),@(20,'LONG_HOME_HOLD_2S'))) {
            $remaining = [int]($step[0] * 1000 - $timeline.ElapsedMilliseconds)
            if ($remaining -gt 0) { Start-Sleep -Milliseconds $remaining }
            Write-Host ("T+" + $step[0] + " " + $step[1])
            Mark ("PHYSICAL_T" + $step[0] + "_" + $step[1])
        }
        $remaining = [int](30000 - $timeline.ElapsedMilliseconds)
        if ($remaining -gt 0) { Start-Sleep -Milliseconds $remaining }
        Capture 'physical-settled'
    } else {
    Mark 'control-menu-back-colours-media'
    foreach ($key in @(82, 4, 183, 184, 185, 186, 85, 85, 89, 90)) { & adb -s $Serial shell input keyevent $key; Start-Sleep -Milliseconds 250 }
    # Use an ordinary app as the starting window; do not play any content.
    & adb -s $Serial shell monkey -p com.spotify.tv.android 1
    Start-Sleep -Seconds 2
    Mark 'short-home'
    & adb -s $Serial shell input keyevent 3
    Start-Sleep -Milliseconds 700
    Capture 'short-home-early'
    Start-Sleep -Seconds 6
    Capture 'short-home-settled'
    & adb -s $Serial shell monkey -p com.spotify.tv.android 1
    Start-Sleep -Seconds 2
    Mark 'long-home'
    & adb -s $Serial shell input keyevent --longpress 3
    Start-Sleep -Seconds 7
    Capture 'long-home-settled'
    }
    Mark 'complete'
} finally {
    # Exact original list, including HoF and any unrelated services. Never replace it with only one service.
    if ($before -eq 'null') { & adb -s $Serial shell settings delete secure enabled_accessibility_services }
    elseif ($before) { & adb -s $Serial shell settings put secure enabled_accessibility_services $before }
    else { & adb -s $Serial shell settings put secure enabled_accessibility_services "''" }
    if ($enabledBefore -eq 'null') { & adb -s $Serial shell settings delete secure accessibility_enabled }
    else { & adb -s $Serial shell settings put secure accessibility_enabled $enabledBefore }
    & adb -s $Serial shell am force-stop tv.aura.probe.homekey
    & adb -s $Serial shell settings get secure enabled_accessibility_services | Set-Content "$evidence\enabled_accessibility_services-after.txt"
    $actualList = ((& adb -s $Serial shell settings get secure enabled_accessibility_services) -join '').Trim()
    $actualEnabled = ((& adb -s $Serial shell settings get secure accessibility_enabled) -join '').Trim()
    $actualEnabled | Set-Content "$evidence\accessibility_enabled-after.txt"
    & adb -s $Serial shell dumpsys accessibility | Set-Content "$evidence\accessibility-after.txt"
    $restoreMismatch = $actualList -ne $before -or $actualEnabled -ne $enabledBefore
    Start-Sleep -Seconds 1
    if ($logger -and -not $logger.HasExited) { Stop-Process -Id $logger.Id }
    Write-Output "Evidence: $evidence"
    if ($restoreMismatch) { throw 'RESTORE MISMATCH: inspect original and current accessibility state; stop tests' }
}
