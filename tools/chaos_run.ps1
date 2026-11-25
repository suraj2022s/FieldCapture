<#
.SYNOPSIS
  Chaos run on the phone: "nothing gets lost", shown with counts rather than claimed.
  Needs the phone to itself for about 5 minutes, with the debug build installed and USB debugging on.

.DESCRIPTION
  One walk on the debug build, driven through adb (DebugCommandReceiver) and the Mock Device Kit
  test server, while things break:
    1. baseline      damage + note + nameplate upload normally (upload latency)
    2. unreachable   adb reverse removed: the phone has a network but no route to the back office
                     (one bar, captive portal, server down); 2 damage + 1 note queue up
    3. app killed    kill -9 with items queued, then relaunched: the walk must resume, same job id
    4. route back    time until the outbox drains (includes WorkManager's backoff)
    5. glasses off   mock power-off mid-walk: capture refused out loud, note still saved;
                     power-on: reconnect time; one more capture
    6. end job       finalize
  Then it compares what the phone acknowledged ("saved", "Noted") with the rows the server stored.

  It uses its own back office (port 8001, temp data dir, extraction off), the synthetic test feed
  (the camera films nothing) and the phone mic off (room speech can't interfere). It never touches
  airplane mode, mobile data or Wi-Fi: on this setup the PC's internet runs through the phone.
  At the end, adb reverse goes back to tcp:8000 -> tcp:8000 and the feed to the phone camera.

.OUTPUTS
  eval/chaos/chaos-<timestamp>.json: counts, timings, verdict, and the app's event log for the run.
#>
param(
  [int]$Port = 8001,
  [int]$OutageSeconds = 60,
  # Skip the "is someone using the phone?" checks.
  [switch]$Force
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'app.fieldcapture'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$work = Join-Path $env:TEMP "fieldcapture-chaos-$stamp"
$dataDir = Join-Path $work 'data'
New-Item -ItemType Directory -Force $dataDir | Out-Null
$outDir = Join-Path $repo 'eval\chaos'
New-Item -ItemType Directory -Force $outDir | Out-Null
$phoneLog = Join-Path $work 'phone.log'
$serverOut = Join-Path $work 'server.out.log'
$serverErr = Join-Path $work 'server.err.log'

function Adb { & $adb @args }
function Step([string]$s) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $s) -ForegroundColor Cyan }

# ---------------------------------------------------------------- phone event log (phone clock)
function Parse([string]$line) {
  if ($line -match '^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+FieldCapture:\s+\[([^\]]+)\]\s?(.*)$') {
    [pscustomobject]@{ t = [double]$matches[1]; type = $matches[2]; detail = $matches[3] }
  }
}
function Events {
  if (-not (Test-Path $phoneLog)) { return @() }
  @(Get-Content $phoneLog | ForEach-Object { Parse $_ } | Where-Object { $_ })
}
function LastT { $e = Events; if ($e.Count) { $e[-1].t } else { 0 } }
function Wait-Event([string]$type, [string]$pattern, [double]$after, [int]$timeoutSec = 30) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $hit = Events | Where-Object { $_.t -gt $after -and $_.type -eq $type -and $_.detail -match $pattern } | Select-Object -First 1
    if ($hit) { return $hit }
    Start-Sleep -Milliseconds 300
  }
  throw "timed out after ${timeoutSec}s waiting for [$type] /$pattern/"
}

# ---------------------------------------------------------------- driving the app and the mock
function Cmd([string]$c, [string]$text = '') {
  if ($text.Contains("'")) { throw "no single quotes in injected text" }
  $before = LastT
  $extra = if ($text) { " --es text '$text'" } else { '' }
  Adb shell "am broadcast -n $pkg/.debug.DebugCommandReceiver --es cmd $c$extra" | Out-Null
  # The receiver logs every command, so this is when it happened on the phone's clock.
  (Wait-Event 'debug_cmd' ('^' + [regex]::Escape($c)) $before 15).t
}
function Mark([string]$what) { Cmd 'mark' $what }
function Status {
  $t = Cmd 'status'
  $e = Wait-Event 'debug_status' '.' ($t - 0.001) 10
  $e.detail
}
function Pending { if ((Status) -match 'pending=(\d+)') { [int]$matches[1] } else { -1 } }
function Wait-Drained([int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((Pending) -eq 0) { return }
    Start-Sleep -Seconds 2
  }
  throw "outbox still not empty after ${timeoutSec}s"
}
function Add-Capture([string]$text) {
  $t = Cmd 'say' $text
  $e = Wait-Event 'capture' 'saved ([0-9a-f]{8})' $t 25
  [void]($e.detail -match 'saved ([0-9a-f]{8})')
  [pscustomobject]@{ id = $matches[1]; savedT = $e.t; detail = $e.detail }
}
function Add-Note([string]$text) {
  $t = Cmd 'say' $text
  $e = Wait-Event 'note' '^([0-9a-f]{8})' $t 15
  [void]($e.detail -match '^([0-9a-f]{8})')
  [pscustomobject]@{ id = $matches[1]; savedT = $e.t }
}
function Get-MockUrl([string]$path) { "http://127.0.0.1:8237$path" }
function Wait-TestServer {
  $deadline = (Get-Date).AddSeconds(30)
  while ((Get-Date) -lt $deadline) {
    try { if ((Invoke-RestMethod (Get-MockUrl '/health') -TimeoutSec 3).status -eq 'ok') { return } } catch { }
    Start-Sleep -Milliseconds 500
  }
  throw "Mock Device Kit test server not reachable on 8237"
}
function DeviceId {
  $r = Invoke-RestMethod (Get-MockUrl '/kit/paired-devices') -TimeoutSec 5
  $d = @($r.pairedDevices)[0]
  if ($d -is [string]) { $d } elseif ($d.deviceId) { $d.deviceId } else { $d.id }
}
function Mock([string]$path) {
  $body = @{ deviceId = (DeviceId) } | ConvertTo-Json
  Invoke-RestMethod -Method Post (Get-MockUrl $path) -ContentType 'application/json' -Body $body -TimeoutSec 10 | Out-Null
}
function Launch {
  Adb shell am start -n "$pkg/.MainActivity" --ez mwdat_ui_testing true | Out-Null
}

# ---------------------------------------------------------------- preflight
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
$devices = @(Adb devices | Select-String '\tdevice$')
if ($devices.Count -ne 1) { throw "need exactly one phone on adb, found $($devices.Count)" }
if (-not $Force) {
  # Someone talking to the app, or a walk still open, means the phone is in use.
  $phoneNow = [double](Adb shell date +%s)
  $recent = Adb logcat -d -v epoch -s 'FieldCapture:I' | ForEach-Object { Parse $_ } |
    Where-Object { $_ -and $_.type -eq 'heard' -and $phoneNow - $_.t -lt 120 }
  if ($recent) { throw "the app heard speech in the last 2 minutes; is someone using the phone? (-Force to override)" }
  $headers = Adb shell "run-as $pkg find files/fieldcapture/jobs -name job.json -exec cat {} +" 2>$null
  $open = [regex]::Matches("$headers", '"jobId":"([^"]+)","createdAt":"[^"]+","endedAt":null')
  if ($open.Count) { throw "open job(s) on the phone: $(($open | ForEach-Object { $_.Groups[1].Value }) -join ', '). End them first (say 'end job'), or -Force." }
  # Queued real uploads would otherwise land in this run's throwaway back office.
  $queuedReal = "$(Adb shell "run-as $pkg ls files/fieldcapture/outbox" 2>$null)".Trim()
  if ($queuedReal) { throw "the phone still has uploads queued for the real back office; let them drain first, or -Force." }
}

$server = $null
$logcat = $null
$result = [ordered]@{
  run = $stamp
  app_commit = (git -C $repo rev-parse --short HEAD)
  phone_model = ((Adb shell getprop ro.product.model).Trim() + ', Android ' + (Adb shell getprop ro.build.version.release).Trim())
  outage_seconds = $OutageSeconds
  phases = [ordered]@{}
}
try {
  Step "back office on :$Port (data in $dataDir, extraction off)"
  $env:FIELDCAPTURE_PORT = "$Port"; $env:FIELDCAPTURE_DATA_DIR = $dataDir; $env:FIELDCAPTURE_EXTRACT = '0'
  $server = Start-Process -FilePath 'uv' -ArgumentList 'run', 'fieldcapture-server' -WorkingDirectory (Join-Path $repo 'backend') `
    -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr -NoNewWindow -PassThru
  Remove-Item Env:FIELDCAPTURE_PORT, Env:FIELDCAPTURE_DATA_DIR, Env:FIELDCAPTURE_EXTRACT
  $deadline = (Get-Date).AddSeconds(60)
  while ($true) {
    try { if ((Invoke-RestMethod "http://127.0.0.1:$Port/healthz" -TimeoutSec 2).ok) { break } } catch { }
    if ((Get-Date) -gt $deadline) { throw "back office didn't start; see $serverErr" }
    Start-Sleep -Milliseconds 500
  }

  Step 'phone log, route, app'
  # A fresh process, so the run sees SDK start-up and the test feed is applied before any stream.
  Adb shell am force-stop $pkg
  Adb logcat -c
  $logcat = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'epoch', '-s', 'FieldCapture:I' `
    -RedirectStandardOutput $phoneLog -NoNewWindow -PassThru
  Adb reverse "tcp:8000" "tcp:$Port" | Out-Null
  Adb forward tcp:8237 tcp:8237 | Out-Null
  Launch
  Wait-Event 'dat' 'SDK ready' 0 30 | Out-Null
  Wait-TestServer
  Cmd 'feed_test' | Out-Null # before the stream starts: a running mock stream keeps its feed

  # 1 -----------------------------------------------------------------
  Step '1/6 baseline'
  $t = Cmd 'start'
  $jobId = ((Wait-Event 'job' 'started (\S+)' ($t - 1) 15).detail -replace '^started ', '')
  Cmd 'mic_off' | Out-Null
  Wait-Event 'stream_state' 'STREAMING' $t 30 | Out-Null
  $caps = @(); $notes = @()
  $caps += Add-Capture 'capture damage, kitchen, north wall'
  $notes += Add-Note 'note: subfloor soft by the cabinet'
  $caps += Add-Capture 'capture nameplate'
  $lat = foreach ($c in $caps) { $s = Wait-Event 'upload' "capture $($c.id) stored" $c.savedT 30; [math]::Round(($s.t - $c.savedT) * 1000) }
  $result.phases.baseline = [ordered]@{ captures = $caps.Count; notes = $notes.Count; save_to_stored_ms = @($lat) }

  # 2 -----------------------------------------------------------------
  Step "2/6 back office unreachable for ${OutageSeconds}s"
  Adb reverse --remove "tcp:8000" | Out-Null
  $tOutage = Mark 'route removed'
  $queued = @()
  $queued += Add-Capture 'capture damage, bathroom, ceiling'
  $notes += Add-Note 'note: stain on the ceiling is still wet'
  $queued += Add-Capture 'capture damage, bathroom, floor'
  $caps += $queued
  $pendingBeforeKill = Pending
  $result.phases.unreachable = [ordered]@{ captures = $queued.Count; notes = 1; pending_on_phone = $pendingBeforeKill }

  # 3 -----------------------------------------------------------------
  Step '3/6 app killed with items queued'
  $appPid = (Adb shell pidof $pkg).Trim()
  $tKill = Mark 'kill'
  Adb shell run-as $pkg kill -9 $appPid
  Start-Sleep -Seconds 3
  Launch
  $resumed = Wait-Event 'job' 'resumed (\S+)' $tKill 40
  $sameJob = $resumed.detail -match [regex]::Escape($jobId)
  Cmd 'mic_off' | Out-Null
  Wait-TestServer
  Wait-Event 'stream_state' 'STREAMING' $resumed.t 30 | Out-Null
  $result.phases.killed = [ordered]@{
    pid = $appPid; resumed_same_job = $sameJob; kill_to_resumed_ms = [math]::Round(($resumed.t - $tKill) * 1000)
    resumed = $resumed.detail; pending_after_restart = (Pending)
  }

  # 4 -----------------------------------------------------------------
  $wait = $OutageSeconds - ((LastT) - $tOutage)
  if ($wait -gt 0) { Step "    outage continues ${wait}s"; Start-Sleep -Seconds ([math]::Ceiling($wait)) }
  Step '4/6 route back; waiting for the outbox to drain'
  Adb reverse "tcp:8000" "tcp:$Port" | Out-Null
  $tBack = Mark 'route restored'
  Wait-Drained 600
  $stored = Events | Where-Object { $_.t -gt $tBack -and $_.type -eq 'upload' -and $_.detail -match 'stored' }
  $retries = Events | Where-Object { $_.t -gt $tOutage -and $_.t -lt $tBack -and $_.type -eq 'upload' -and $_.detail -match 'retry' }
  $result.phases.route_back = [ordered]@{
    outage_ms = [math]::Round(($tBack - $tOutage) * 1000)
    upload_attempts_during_outage = @($retries).Count
    restored_to_first_stored_ms = if ($stored) { [math]::Round((@($stored)[0].t - $tBack) * 1000) } else { $null }
    restored_to_last_stored_ms = if ($stored) { [math]::Round((@($stored)[-1].t - $tBack) * 1000) } else { $null }
    items_stored = @($stored).Count
  }

  # 5 -----------------------------------------------------------------
  Step '5/6 glasses powered off mid-walk'
  Mock '/device/power-off'
  $tOff = Mark 'power off'
  $lost = Wait-Event 'say' 'Glasses disconnected' ($tOff - 2) 30
  $t = Cmd 'say' 'capture damage, hallway, wall'
  $refused = Wait-Event 'capture_refused' '.' $t 15
  $notes += Add-Note 'note: hallway carpet damp near the door'
  # As the tech would: power the glasses back on, open them, put them on (same as LifecycleTest L7).
  Mock '/device/power-on'
  $tOn = Mark 'power on'
  Mock '/device/unfold'
  Mock '/device/don'
  $back = Wait-Event 'say' 'Glasses back' $tOn 60
  $caps += Add-Capture 'capture damage, hallway, wall'
  $result.phases.glasses_off = [ordered]@{
    off_to_spoken_ms = [math]::Round(($lost.t - $tOff) * 1000)
    capture_while_off = $refused.detail
    on_to_glasses_back_ms = [math]::Round(($back.t - $tOn) * 1000)
  }

  # 6 -----------------------------------------------------------------
  Step '6/6 end job'
  $t = Cmd 'end'
  Wait-Event 'job' 'ended' $t 15 | Out-Null
  Wait-Drained 300

  # ---------------------------------------------------------------- compare
  Step 'comparing phone and back office'
  Start-Sleep -Seconds 2 # let the server finish logging the last answers
  $srv = (& uv run --directory (Join-Path $repo 'backend') python (Join-Path $repo 'tools\chaos_counts.py') $dataDir $jobId $serverErr) | ConvertFrom-Json
  $phoneCaps = @($caps | ForEach-Object { $_.id } | Sort-Object)
  $phoneNotes = @($notes | ForEach-Object { $_.id } | Sort-Object)
  $missing = @($phoneCaps + $phoneNotes | Where-Object { $_ -notin (@($srv.captures) + @($srv.notes)) })
  $extra = @(@($srv.captures) + @($srv.notes) | Where-Object { $_ -notin ($phoneCaps + $phoneNotes) })
  $result.job_id = $jobId
  $result.phone = [ordered]@{ captures_acknowledged = $phoneCaps.Count; notes_acknowledged = $phoneNotes.Count; captures_refused_while_offline = 1 }
  $result.server = $srv
  $result.missing_on_server = $missing
  $result.unexpected_on_server = $extra
  $result.verdict = if ($missing.Count -eq 0 -and $extra.Count -eq 0 -and $sameJob -and $srv.finalized) { 'PASS' } else { 'FAIL' }
}
catch {
  $result.error = "$_"
  $result.verdict = 'ERROR'
  Write-Host "chaos run failed: $_" -ForegroundColor Red
}
finally {
  Step 'cleanup: chaos job ended and drained, feed back to the phone camera, route back to :8000'
  # A run that failed half-way must not leave its job open (the next launch would resume it) or
  # its uploads queued (they would go to the real back office once the route is restored).
  try { Cmd 'end' | Out-Null } catch { }
  try {
    Adb reverse "tcp:8000" "tcp:$Port" | Out-Null
    Wait-Drained 90
  } catch {
    Write-Host "WARNING: the phone still has chaos-run uploads queued; they will go to the :8000 back office." -ForegroundColor Yellow
  }
  try { Cmd 'feed_camera' | Out-Null } catch { }
  Adb reverse "tcp:8000" "tcp:8000" | Out-Null
  if ($server) { & taskkill /PID $server.Id /T /F 2>$null | Out-Null }
  if ($logcat) { Start-Sleep -Milliseconds 500; & taskkill /PID $logcat.Id /T /F 2>$null | Out-Null }
  # The run's event log, minus anything the phone mic heard (room speech is not data).
  $result.events = @(Events | Where-Object { $_.type -ne 'heard' } | ForEach-Object { '{0:F3} [{1}] {2}' -f $_.t, $_.type, $_.detail })
  $out = Join-Path $outDir "chaos-$stamp.json"
  $result | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 $out
  Write-Host "verdict: $($result.verdict)  ->  $out" -ForegroundColor Green
  Write-Host "raw logs and server data: $work"
}
