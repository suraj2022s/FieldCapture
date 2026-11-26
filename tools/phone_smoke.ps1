# Phone smoke test (~1 min): is the phone ready for a filming session or the demo?
#   A. One job on the synthetic test feed: capture nameplate, capture damage, a note, end job. Checks voice
#      command -> quality gate -> save -> dataset-mode frames -> upload -> extraction in the back office.
#      Nothing from the room is recorded; the smoke test's dataset frames are deleted afterwards.
#   B. The phone's back camera as the glasses feed: stream only, no captures, nothing saved.
# Needs a back office with extraction on, listening on -Port (the app's 127.0.0.1:8000 is reversed to it).
# Usage (repo root):  powershell -ExecutionPolicy Bypass -File tools\phone_smoke.ps1 [-Port 8002] [-Force]
param([int]$Port = 8002, [string]$OutDir = (Join-Path $env:TEMP 'fieldcapture-smoke'), [switch]$Force)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'app.fieldcapture'
$dir = $OutDir
New-Item -ItemType Directory -Force $dir | Out-Null
$phoneLog = Join-Path $dir 'smoke-phone.log'
$recordOut = Join-Path $dir 'smoke-record.json'

function Adb { $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'; try { & $adb @args 2>$null } finally { $ErrorActionPreference = $eap } }
function Step([string]$s) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $s) -ForegroundColor Cyan }
function Parse([string]$line) {
  if ($line -match '^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+FieldCapture:\s+\[([^\]]+)\]\s?(.*)$') {
    [pscustomobject]@{ t = [double]$matches[1]; type = $matches[2]; detail = $matches[3] }
  }
}
function Events { if (-not (Test-Path $phoneLog)) { return @() }; @(Get-Content $phoneLog | ForEach-Object { Parse $_ } | Where-Object { $_ }) }
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
function Cmd([string]$c, [string]$text = '') {
  $before = LastT
  $extra = if ($text) { " --es text '$text'" } else { '' }
  Adb shell "am broadcast -n $pkg/.debug.DebugCommandReceiver --es cmd $c$extra" | Out-Null
  (Wait-Event 'debug_cmd' ('^' + [regex]::Escape($c)) $before 15).t
}
function Status { $t = Cmd 'status'; (Wait-Event 'debug_status' '.' ($t - 0.001) 10).detail }
function Pending { if ((Status) -match 'pending=(\d+)') { [int]$matches[1] } else { -1 } }
function Launch([double]$after) {
  Adb shell am force-stop $pkg
  Adb shell am start -n "$pkg/.MainActivity" | Out-Null
  (Wait-Event 'dat' 'SDK ready' $after 40).detail
}

# ---------------------------------------------------------------- preflight (same rules as chaos_run.ps1)
if (@(Adb devices | Select-String '\tdevice$').Count -ne 1) { throw 'need exactly one phone on adb' }
if (-not (Invoke-RestMethod "http://127.0.0.1:$Port/healthz" -TimeoutSec 5).extract) { throw "the back office on :$Port has extraction off" }
if (-not $Force) {
  $phoneNow = [double](Adb shell date +%s)
  $recent = Adb logcat -d -v epoch -s 'FieldCapture:I' | ForEach-Object { Parse $_ } |
    Where-Object { $_ -and $_.type -eq 'heard' -and $phoneNow - $_.t -lt 120 }
  if ($recent) { throw 'the app heard speech in the last 2 minutes; is someone using the phone? (-Force to override)' }
  $headers = Adb shell "run-as $pkg find files/fieldcapture/jobs -name job.json -exec cat {} +"
  if ([regex]::IsMatch("$headers", '"endedAt":null')) { throw "a job is open on the phone; end it first (say 'end job'), or -Force" }
  if ("$(Adb shell "run-as $pkg ls files/fieldcapture/outbox")".Trim()) { throw 'uploads are still queued on the phone; let them drain first, or -Force' }
  if ("$(Adb shell 'ls /sdcard/Android/data/app.fieldcapture/files/dataset/')".Trim()) { throw 'the dataset folder holds captures; pull them first (eval\pull_dataset.ps1), or -Force' }
}

$results = [ordered]@{ app_commit = (git -C (Split-Path -Parent $PSScriptRoot) rev-parse --short HEAD) }
$logcat = $null
try {
  Step "phone log + route 8000 -> :$Port"
  Adb logcat -c
  $logcat = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'epoch', '-s', 'FieldCapture:I' -RedirectStandardOutput $phoneLog -NoNewWindow -PassThru
  Adb reverse tcp:8000 "tcp:$Port" | Out-Null
  Start-Sleep -Seconds 1
  $results.sdk = Launch 0
  Write-Host "  $($results.sdk)"

  # ---------------------------------------------------------------- A: full pipeline on the test feed
  Step 'A: test feed + dataset mode, start job'
  Cmd 'feed_test' | Out-Null
  Cmd 'dataset_on' | Out-Null
  $t = Cmd 'start'
  $jobId = ((Wait-Event 'job' 'started (\S+)' ($t - 1) 15).detail -replace '^started ', '')
  $asr = Wait-Event 'asr' 'engine=' ($t - 5) 15
  $results.asr = $asr.detail
  Write-Host "  job $jobId; mic: $($asr.detail)"
  Cmd 'mic_off' | Out-Null   # nobody speaks during the test; room sound must not trigger commands
  $st = Wait-Event 'stream_state' 'STREAMING' $t 40
  $results.stream_start_s = [math]::Round($st.t - $t, 1)
  Write-Host "  streaming after $($results.stream_start_s) s"
  Start-Sleep -Seconds 3

  $caps = @()
  foreach ($say in 'capture nameplate', 'capture damage, kitchen, north wall') {
    $t1 = Cmd 'say' $say
    $e = Wait-Event 'capture' 'saved ([0-9a-f]{8})' $t1 30
    [void]($e.detail -match 'saved ([0-9a-f]{8})'); $id = $matches[1]
    $ds = Wait-Event 'dataset' 'frames ->' $t1 20
    $up = Wait-Event 'upload' "capture $id stored" $e.t 40
    $caps += [ordered]@{ said = $say; saved_s = [math]::Round($e.t - $t1, 1); upload_s = [math]::Round($up.t - $e.t, 1); detail = $e.detail; dataset = $ds.detail }
    Write-Host "  '$say' -> saved in $([math]::Round($e.t - $t1, 1)) s, uploaded $([math]::Round($up.t - $e.t, 1)) s later"
    Write-Host "     $($e.detail)"
    Write-Host "     dataset: $($ds.detail)"
  }
  $results.captures = $caps
  $t2 = Cmd 'say' 'note: phone smoke test, please ignore'
  $n = Wait-Event 'note' '^([0-9a-f]{8})' $t2 15
  Write-Host "  note saved: $($n.detail)"
  $results.status_test_feed = Status
  Write-Host "  $($results.status_test_feed)"

  Step 'A: end job, wait for uploads'
  Cmd 'dataset_off' | Out-Null
  $t3 = Cmd 'end'
  Wait-Event 'job' 'ended' ($t3 - 1) 15 | Out-Null
  $deadline = (Get-Date).AddSeconds(60)
  while ((Pending) -ne 0) { if ((Get-Date) -gt $deadline) { throw 'outbox not drained after 60 s' }; Start-Sleep -Seconds 2 }
  Write-Host '  outbox drained'

  # The back office extracts in the background; wait until the record stops changing.
  $deadline = (Get-Date).AddSeconds(150)
  $prev = ''; $same = 0
  do {
    Start-Sleep -Seconds 5
    $raw = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/v1/jobs/$jobId/record" -TimeoutSec 10).Content
    if ($raw -eq $prev) { $same++ } else { $same = 0 }
    $prev = $raw
  } while ($same -lt 2 -and (Get-Date) -lt $deadline)
  Set-Content -Path $recordOut -Value $raw -Encoding utf8
  Write-Host "  record saved to $recordOut ($($raw.Length) bytes)"

  # Remove the smoke test's dataset frames so the folder is empty for real filming.
  $results.dataset_dirs = @(Adb shell 'ls /sdcard/Android/data/app.fieldcapture/files/dataset/')
  if ($jobId -notmatch '^[0-9a-f-]{36}$') { throw "unexpected job id '$jobId'" }
  Adb shell "rm -rf /sdcard/Android/data/app.fieldcapture/files/dataset/$jobId" | Out-Null
  $left = "$(Adb shell 'ls /sdcard/Android/data/app.fieldcapture/files/dataset/')".Trim()
  Write-Host "  smoke-test frames deleted (dataset folder now: '$left')"

  # ---------------------------------------------------------------- B: phone camera, stream only
  Step 'B: phone back camera, stream check (no captures)'
  Cmd 'feed_camera' | Out-Null
  $tl = LastT
  $results.sdk_camera = Launch $tl
  $t = Cmd 'start'
  $jobB = ((Wait-Event 'job' 'started (\S+)' ($t - 1) 15).detail -replace '^started ', '')
  Cmd 'mic_off' | Out-Null
  $st = Wait-Event 'stream_state' 'STREAMING' $t 40
  Write-Host "  streaming after $([math]::Round($st.t - $t, 1)) s"
  Start-Sleep -Seconds 8
  $results.status_camera = Status
  Write-Host "  $($results.status_camera)"
  $t4 = Cmd 'end'
  Wait-Event 'job' 'ended' ($t4 - 1) 15 | Out-Null
  $deadline = (Get-Date).AddSeconds(60)
  while ((Pending) -ne 0) { if ((Get-Date) -gt $deadline) { throw 'outbox not drained after 60 s' }; Start-Sleep -Seconds 2 }
  Write-Host "  job $jobB ended, outbox drained"
  $results.jobs = @($jobId, $jobB)
  $results.result = 'PASS'
} catch {
  $results.result = "ERROR: $($_.Exception.Message)"
  Write-Host $results.result -ForegroundColor Red
  try { Cmd 'dataset_off' | Out-Null; Cmd 'feed_camera' | Out-Null; Cmd 'end' | Out-Null } catch { }
} finally {
  if ($logcat) { Stop-Process -Id $logcat.Id -ErrorAction SilentlyContinue }
  $results | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir 'smoke-result.json') -Encoding utf8
  Step "done: $($results.result)"
}
