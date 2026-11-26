# Glasses events through the Mock Device Kit test server (port 8237): touchpad tap / tap-and-hold, camera
# permission revoked, firmware update required, charging, unpaired, registration revoked. A capture before
# and after each, plus what the app says. Synthetic test feed: nothing is filmed. Uploads go to the :8002
# back office via the usual route (a handful of captures).
# Usage (repo root):  powershell -ExecutionPolicy Bypass -File tools\mdk_scenarios.ps1  ->  eval/chaos/mdk-scenarios-<ts>.txt
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'app.fieldcapture'
$repo = Split-Path -Parent $PSScriptRoot; $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'; $dir = Join-Path $env:TEMP "fieldcapture-mdk-$stamp"; New-Item -ItemType Directory -Force $dir | Out-Null
$phoneLog = Join-Path $dir 'mdk-phone.log'
$out = Join-Path $repo "eval\chaos\mdk-scenarios-$stamp.txt"
$MockApi = 'http://127.0.0.1:8237'
Set-Content $out ''
function W([string]$s) { Write-Host $s; Add-Content $out $s }
function Adb { $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'; try { & $adb @args 2>$null } finally { $ErrorActionPreference = $eap } }
function Parse([string]$line) {
  if ($line -match '^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+FieldCapture:\s+\[([^\]]+)\]\s?(.*)$') { [pscustomobject]@{ t = [double]$matches[1]; type = $matches[2]; detail = $matches[3] } }
}
function Events { if (-not (Test-Path $phoneLog)) { return @() }; @(Get-Content $phoneLog | ForEach-Object { Parse $_ } | Where-Object { $_ }) }
function LastT { $e = Events; if ($e.Count) { $e[-1].t } else { 0 } }
function Wait-Event([string]$type, [string]$pattern, [double]$after, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $hit = Events | Where-Object { $_.t -gt $after -and $_.type -eq $type -and $_.detail -match $pattern } | Select-Object -First 1
    if ($hit) { return $hit }
    Start-Sleep -Milliseconds 400
  }
  $null
}
function Cmd([string]$c, [string]$text = '') {
  $before = LastT
  $extra = if ($text) { " --es text '$text'" } else { '' }
  Adb shell "am broadcast -n $pkg/.debug.DebugCommandReceiver --es cmd $c$extra" | Out-Null
  $e = Wait-Event 'debug_cmd' ('^' + [regex]::Escape($c)) $before 15
  if (-not $e) { throw "phone didn't log debug_cmd $c" }
  $e.t
}
function Status { $t = Cmd 'status'; $e = Wait-Event 'debug_status' '.' ($t - 0.001) 10; $e.detail -replace '^job=\S+ ', '' }
function DeviceId { $r = Invoke-RestMethod "$MockApi/kit/paired-devices" -TimeoutSec 5; $d = @($r.pairedDevices)[0]; if ($d -is [string]) { $d } elseif ($d.deviceId) { $d.deviceId } else { $d.id } }
function Mock([string]$path, [hashtable]$body = @{}, [switch]$NoDevice) {
  if (-not $NoDevice) { $body.deviceId = DeviceId }
  Invoke-RestMethod -Method Post "$MockApi$path" -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 10
}
function TryCapture([string]$label) {
  $t = Cmd 'say' 'capture damage kitchen north wall'
  $deadline = (Get-Date).AddSeconds(14)
  while ((Get-Date) -lt $deadline) {
    $e = Events | Where-Object { $_.t -gt $t -and ($_.type -eq 'capture_refused' -or ($_.type -eq 'capture' -and $_.detail -match 'saved|abandoned|no frames|crashed')) } | Select-Object -First 1
    if ($e) { W "   capture $label -> [$($e.type)] $($e.detail -replace '(saved \w+):.*', '$1')"; return }
    Start-Sleep -Milliseconds 400
  }
  W "   capture $label -> no result in 14 s"
}
function Show([double]$after) {
  Events | Where-Object { $_.t -gt $after -and $_.type -match '^(phase|say|alert|session_error|stream_error|reconnect|permission|registration|walk|device)$' } |
    ForEach-Object { W ("     {0,5:N1}s [{1}] {2}" -f ($_.t - $after), $_.type, $_.detail) }
}
function Ready([string]$label, [int]$timeoutSec = 25) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) { $s = Status; if ($s -match 'phase=ACTIVE .*stream=STREAMING') { W "   ${label}: back to ACTIVE"; return $true }; Start-Sleep -Seconds 2 }
  W "   ${label}: NOT back to ACTIVE in ${timeoutSec}s ($(Status))"; $false
}

$logcat = $null
try {
  Adb shell am force-stop $pkg
  Adb logcat -c
  $logcat = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'epoch', '-s', 'FieldCapture:I' -RedirectStandardOutput $phoneLog -NoNewWindow -PassThru
  Adb reverse tcp:8000 tcp:8002 | Out-Null
  Adb forward tcp:8237 tcp:8237 | Out-Null
  Adb shell am start -n "$pkg/.MainActivity" --ez mwdat_ui_testing true | Out-Null
  if (-not (Wait-Event 'dat' 'SDK ready' 0 40)) { throw 'app did not start' }
  $deadline = (Get-Date).AddSeconds(30); while ((Get-Date) -lt $deadline) { try { if ((Invoke-RestMethod "$MockApi/health" -TimeoutSec 3).status -eq 'ok') { break } } catch { }; Start-Sleep -Milliseconds 500 }
  Cmd 'feed_test' | Out-Null
  $t = Cmd 'start'; Cmd 'mic_off' | Out-Null
  if (-not (Wait-Event 'stream_state' 'STREAMING' $t 30)) { throw 'no stream' }
  W "start: $(Status)"
  TryCapture 'baseline'

  W "`n=== 1a. touchpad tap on the glasses (the glasses pause the camera), tap again"
  $t = Cmd 'mark' 'tap'; Mock '/captouch/tap' | Out-Null; Start-Sleep -Seconds 3
  W "   after tap: $(Status)"; TryCapture 'while device-paused'
  Mock '/captouch/tap' | Out-Null; Start-Sleep -Seconds 3; Show $t; [void](Ready 'second tap'); TryCapture 'after'

  W "`n=== 1b. touchpad tap, then the tech says 'resume the job'"
  $t = Cmd 'mark' 'tap2'; Mock '/captouch/tap' | Out-Null; Start-Sleep -Seconds 3
  Cmd 'say' 'resume the job' | Out-Null; Start-Sleep -Seconds 4; Show $t; [void](Ready 'voice resume'); TryCapture 'after'

  W "`n=== 1c. touchpad tap-and-hold (stops the session)"
  $t = Cmd 'mark' 'hold'; Mock '/captouch/tap-and-hold' | Out-Null; Start-Sleep -Seconds 8
  W "   8 s later: $(Status)"; Show $t; [void](Ready 'after tap-and-hold'); TryCapture 'after'

  W "`n=== 2. camera permission revoked in Meta AI, glasses reconnect, permission granted again"
  $t = Cmd 'mark' 'perm'
  Mock '/permissions/set' @{ permission = 'camera'; status = 'denied' } -NoDevice | Out-Null
  Mock '/device/power-off' | Out-Null; Start-Sleep -Seconds 7; Mock '/device/power-on' | Out-Null; Start-Sleep -Seconds 9
  W "   denied + reconnect: $(Status)"; TryCapture 'while denied'
  Mock '/permissions/set' @{ permission = 'camera'; status = 'granted' } -NoDevice | Out-Null; Start-Sleep -Seconds 4
  Show $t; [void](Ready 'granted again'); TryCapture 'after'

  W "`n=== 3. glasses firmware too old (update required), then updated"
  $t = Cmd 'mark' 'fw'
  Mock '/services/set-firmware-update-requirement-enabled' @{ enabled = $true } | Out-Null
  Mock '/device/power-off' | Out-Null; Start-Sleep -Seconds 7; Mock '/device/power-on' | Out-Null; Start-Sleep -Seconds 15
  W "   update required: $(Status)"; TryCapture 'while update required'
  Mock '/services/set-firmware-update-requirement-enabled' @{ enabled = $false } | Out-Null
  Mock '/device/power-off' | Out-Null; Start-Sleep -Seconds 6; Mock '/device/power-on' | Out-Null; Start-Sleep -Seconds 4
  Show $t; [void](Ready 'firmware updated' 40); TryCapture 'after'

  W "`n=== 4. glasses report charging, then not"
  $t = Cmd 'mark' 'charge'
  Mock '/device/set-charging-state' @{ state = 'charging' } | Out-Null; Start-Sleep -Seconds 4
  W "   charging: $(Status)"; TryCapture 'while charging'
  Mock '/device/set-charging-state' @{ state = 'notCharging' } | Out-Null; Start-Sleep -Seconds 2; Show $t

  W "`n=== 5. glasses unpaired mid-walk, then paired again"
  $t = Cmd 'mark' 'unpair'
  Mock '/kit/unpair-device' | Out-Null; Start-Sleep -Seconds 8
  W "   unpaired: $(Status)"; TryCapture 'while unpaired'
  $p = Mock '/kit/pair-glasses' @{ model = 'rayBanMeta' } -NoDevice
  Mock '/device/power-on' | Out-Null; Mock '/device/don' | Out-Null
  try { Mock '/camera/set-camera-feed/resource' @{ resourceName = 'test_feed'; ext = 'mp4' } | Out-Null; W '   new pair: test feed set' } catch { W "   new pair: feed not set ($($_.Exception.Message))" }
  Start-Sleep -Seconds 6; Show $t; [void](Ready 're-paired' 40); TryCapture 'after'

  W "`n=== 6. registration with Meta AI revoked (kit re-enabled unregistered), then registered again"
  $t = Cmd 'mark' 'reg'
  Mock '/kit/disable' -NoDevice | Out-Null; Mock '/kit/enable' @{ initiallyRegistered = $false } -NoDevice | Out-Null; Start-Sleep -Seconds 8
  W "   unregistered: $(Status)"; TryCapture 'while unregistered'
  Mock '/kit/disable' -NoDevice | Out-Null; Mock '/kit/enable' @{ initiallyRegistered = $true } -NoDevice | Out-Null
  Mock '/kit/pair-glasses' @{ model = 'rayBanMeta' } -NoDevice | Out-Null; Mock '/device/power-on' | Out-Null; Mock '/device/don' | Out-Null
  try { Mock '/camera/set-camera-feed/resource' @{ resourceName = 'test_feed'; ext = 'mp4' } | Out-Null } catch { }
  Start-Sleep -Seconds 6; Show $t; [void](Ready 're-registered' 45); TryCapture 'after'

  W "`n=== end"
  Cmd 'end' | Out-Null; Start-Sleep -Seconds 3
  $crash = (Adb logcat -b crash -d) -join "`n"
  W ("crash buffer: " + $(if ($crash -match 'FATAL EXCEPTION' -and $crash -match $pkg) { 'CRASH' } else { 'clean' }))
} catch {
  W "ERROR: $($_.Exception.Message)"
} finally {
  # The test feed setting persists: put the phone camera back, or the next real walk films a test pattern.
  Adb shell "am broadcast -n $pkg/.debug.DebugCommandReceiver --es cmd feed_camera" | Out-Null
  if ($logcat) { & taskkill /PID $logcat.Id /T /F 2>$null | Out-Null }
}
