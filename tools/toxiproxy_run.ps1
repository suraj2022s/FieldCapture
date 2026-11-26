# Bad-signal test of the upload path on the phone, with Toxiproxy (https://github.com/Shopify/toxiproxy, MIT)
# between the app and a throwaway back office:
#   phone 127.0.0.1:8000 --adb reverse--> PC :8005 --Toxiproxy--> back office :8001 (extraction off, temp data)
#
#   -Suite faults   S1 normal, S2 2-3 s latency, S3 8 KB/s uplink, S4 connections reset for 40 s,
#                   S5 replies lost (server stored it, phone never hears), S6 back office down for 150 s
#   -Suite outages  R1 connections reset for 40 s, R2 back office down for 90 s and for 200 s
#
# Each case: captures through the debug driver on the synthetic test feed (mic off, nothing filmed), then
# how long the phone took to deliver, and at the end what the server stored per capture (chaos_counts.py).
# Usage (repo root):  powershell -ExecutionPolicy Bypass -File tools\toxiproxy_run.ps1 [-Suite outages] [-Force]
# Needs toxiproxy-server 2.13.0 (Windows binary, checksum-verified) in -ToxDir. Writes eval/chaos/toxiproxy-<suite>-<ts>.txt.
param(
  [ValidateSet('faults', 'outages')][string]$Suite = 'faults',
  [string]$ToxDir = (Join-Path $env:LOCALAPPDATA 'fc-tools\toxiproxy-2.13.0'),
  [switch]$Force
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'app.fieldcapture'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$work = Join-Path $env:TEMP "fieldcapture-tox-$stamp"
New-Item -ItemType Directory -Force $work | Out-Null
$phoneLog = Join-Path $work 'phone.log'
$out = Join-Path $repo "eval\chaos\toxiproxy-$Suite-$stamp.txt"
$ToxApi = 'http://127.0.0.1:8474'   # not $T: PowerShell names are case-insensitive, and $t is a timestamp below
Set-Content $out "# tools/toxiproxy_run.ps1 -Suite $Suite, $(Get-Date -Format s), app commit $(git -C $repo rev-parse --short HEAD)"
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
function Pending { $t = Cmd 'status'; $e = Wait-Event 'debug_status' '.' ($t - 0.001) 10; if ($e.detail -match 'pending=(\d+)') { [int]$matches[1] } else { -1 } }
function Capture([string]$say) {
  $t = Cmd 'say' $say
  $e = Wait-Event 'capture' 'saved ([0-9a-f]{8})' $t 25
  if (-not $e) { throw "capture not saved: $say" }
  [void]($e.detail -match 'saved ([0-9a-f]{8})')
  [pscustomobject]@{ id = $matches[1]; savedT = $e.t }
}
function Stored($c, [int]$timeoutSec) { Wait-Event 'upload' "capture $($c.id) stored" $c.savedT $timeoutSec }
function Secs($e, [double]$from, [string]$limit) { if ($e) { '{0:N1}s' -f ($e.t - $from) } else { "NOT within $limit" } }
function TP([string]$method, [string]$path, $body = $null) {
  # Toxiproxy rejects browser-like user agents (CSRF guard); PowerShell's default contains "Mozilla".
  if ($body) { Invoke-RestMethod -UserAgent 'fieldcapture-tox' -Method $method -Uri "$ToxApi$path" -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress -Depth 5) }
  else { Invoke-RestMethod -UserAgent 'fieldcapture-tox' -Method $method -Uri "$ToxApi$path" }
}
function Toxic([string]$name, [string]$type, [string]$stream, [hashtable]$attrs) {
  TP POST '/proxies/bo/toxics' @{ name = $name; type = $type; stream = $stream; toxicity = 1.0; attributes = $attrs } | Out-Null
}
function Untoxic([string]$name) { TP DELETE "/proxies/bo/toxics/$name" | Out-Null }
function Drain([int]$timeoutSec) {
  $t0 = Get-Date
  while (((Get-Date) - $t0).TotalSeconds -lt $timeoutSec) { if ((Pending) -eq 0) { return [math]::Round(((Get-Date) - $t0).TotalSeconds, 1) }; Start-Sleep -Seconds 3 }
  -1
}
function UploadLog([double]$after) { Events | Where-Object { $_.t -gt $after -and $_.type -eq 'upload' } | ForEach-Object { W ("     {0:N1}s {1}" -f ($_.t - $after), $_.detail) } }
function Up([string]$url) { try { [void](Invoke-RestMethod -UserAgent 'fieldcapture-tox' $url -TimeoutSec 2); $true } catch { $false } }

# ---------------------------------------------------------------- preflight (same rules as chaos_run.ps1)
if (@(Adb devices | Select-String '\tdevice$').Count -ne 1) { throw 'need exactly one phone on adb' }
if (-not $Force) {
  $phoneNow = [double](Adb shell date +%s)
  $recent = Adb logcat -d -v epoch -s 'FieldCapture:I' | ForEach-Object { Parse $_ } | Where-Object { $_ -and $_.type -eq 'heard' -and $phoneNow - $_.t -lt 120 }
  if ($recent) { throw 'the app heard speech in the last 2 minutes; is someone using the phone? (-Force to override)' }
  if ([regex]::IsMatch("$(Adb shell "run-as $pkg find files/fieldcapture/jobs -name job.json -exec cat {} +")", '"endedAt":null')) { throw 'a job is open on the phone; end it first, or -Force' }
  if ("$(Adb shell "run-as $pkg ls files/fieldcapture/outbox")".Trim()) { throw 'uploads are still queued on the phone; let them drain first, or -Force' }
}

$started = @()
$logcat = $null
$all = @()
$job = $null
$serverLog = Join-Path $work 'server.log'
try {
  if (-not (Up "$ToxApi/version")) {
    $started += Start-Process -FilePath (Join-Path $ToxDir 'toxiproxy-server-windows-amd64.exe') -ArgumentList '-host', '127.0.0.1', '-port', '8474' -RedirectStandardOutput (Join-Path $work 'tox.log') -RedirectStandardError (Join-Path $work 'tox.err') -NoNewWindow -PassThru
  }
  $ownServer = -not (Up 'http://127.0.0.1:8001/healthz')
  if ($ownServer) {
    $env:FIELDCAPTURE_PORT = '8001'; $env:FIELDCAPTURE_DATA_DIR = (Join-Path $work 'data'); $env:FIELDCAPTURE_EXTRACT = '0'
    $started += Start-Process -FilePath 'uv' -ArgumentList 'run', 'fieldcapture-server' -WorkingDirectory (Join-Path $repo 'backend') -RedirectStandardOutput (Join-Path $work 'server.out') -RedirectStandardError $serverLog -NoNewWindow -PassThru
    Remove-Item Env:FIELDCAPTURE_PORT, Env:FIELDCAPTURE_DATA_DIR, Env:FIELDCAPTURE_EXTRACT
  }
  $deadline = (Get-Date).AddSeconds(60)
  while (-not ((Up "$ToxApi/version") -and (Up 'http://127.0.0.1:8001/healthz'))) { if ((Get-Date) -gt $deadline) { throw 'Toxiproxy or the :8001 back office did not start' }; Start-Sleep -Milliseconds 500 }
  if ((Invoke-RestMethod 'http://127.0.0.1:8001/healthz').extract) { throw 'the :8001 back office has extraction on; refusing (it would spend API money)' }

  try { TP DELETE '/proxies/bo' | Out-Null } catch { }
  TP POST '/proxies' @{ name = 'bo'; listen = '127.0.0.1:8005'; upstream = '127.0.0.1:8001'; enabled = $true } | Out-Null
  Adb shell am force-stop $pkg
  Adb logcat -c
  $logcat = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'epoch', '-s', 'FieldCapture:I' -RedirectStandardOutput $phoneLog -NoNewWindow -PassThru
  Adb reverse tcp:8000 tcp:8005 | Out-Null
  Adb shell am start -n "$pkg/.MainActivity" | Out-Null
  if (-not (Wait-Event 'dat' 'SDK ready' 0 40)) { throw 'app did not start' }
  Cmd 'feed_test' | Out-Null
  $t = Cmd 'start'
  $job = (Wait-Event 'job' 'started (\S+)' ($t - 1) 15).detail -replace '^started ', ''
  Cmd 'mic_off' | Out-Null
  if (-not (Wait-Event 'stream_state' 'STREAMING' $t 30)) { throw 'no stream' }
  W "job $job"

  if ($Suite -eq 'faults') {
    W "`n=== S1 normal"
    $c = Capture 'capture damage kitchen north wall'; $all += $c
    W ("   stored after " + (Secs (Stored $c 20) $c.savedT '20s'))

    W "`n=== S2 latency 2000+-500 ms down, 1000 ms up"
    Toxic 'lat_down' 'latency' 'downstream' @{ latency = 2000; jitter = 500 }; Toxic 'lat_up' 'latency' 'upstream' @{ latency = 1000; jitter = 0 }
    $c = Capture 'capture damage kitchen south wall'; $all += $c
    W ("   stored after " + (Secs (Stored $c 60) $c.savedT '60s'))
    Untoxic 'lat_down'; Untoxic 'lat_up'

    W "`n=== S3 slow uplink 8 KB/s"
    Toxic 'bw' 'bandwidth' 'upstream' @{ rate = 8 }
    $c = Capture 'capture damage kitchen east wall'; $all += $c
    W ("   stored after " + (Secs (Stored $c 120) $c.savedT '120s'))
    Untoxic 'bw'
  }

  W "`n=== $(if ($Suite -eq 'faults') { 'S4' } else { 'R1' }) connection reset 300 ms into every upload, for 40 s"
  Toxic 'rst' 'reset_peer' 'upstream' @{ timeout = 300 }
  $c = Capture 'capture damage kitchen west wall'; $all += $c
  Start-Sleep -Seconds 40
  W ("   pending while resetting: {0}" -f (Pending))
  Untoxic 'rst'; $tRel = Cmd 'mark' 'restored'
  W ("   stored " + (Secs (Stored $c 300) $tRel '300s') + " after the resets stopped")
  UploadLog $c.savedT

  if ($Suite -eq 'faults') {
    W "`n=== S5 server stores it, but the reply never reaches the phone"
    Toxic 'blackhole' 'timeout' 'downstream' @{ timeout = 0 }
    $c = Capture 'capture damage hallway left wall'; $all += $c
    W ("   phone gave up waiting after " + (Secs (Wait-Event 'upload' 'no connection' $c.savedT 90) $c.savedT '90s'))
    Untoxic 'blackhole'; $tRel = Cmd 'mark' 'restored'
    W ("   stored " + (Secs (Stored $c 300) $tRel '300s') + " after the replies came back")
    UploadLog $c.savedT
  }

  $outages = if ($Suite -eq 'faults') { @(150) } else { @(90, 200) }
  foreach ($outage in $outages) {
    W "`n=== $(if ($Suite -eq 'faults') { 'S6' } else { 'R2' }) back office unreachable for $outage s, captures during it"
    TP POST '/proxies/bo' @{ enabled = $false } | Out-Null
    $tDown = LastT
    $c = Capture 'capture damage bathroom ceiling'; $all += $c
    Start-Sleep -Seconds ([int]($outage / 2))
    $c = Capture 'capture damage bathroom floor'; $all += $c
    Start-Sleep -Seconds ([int]($outage / 2))
    TP POST '/proxies/bo' @{ enabled = $true } | Out-Null
    $tRel = Cmd 'mark' 'restored'
    W ("   last capture stored " + (Secs (Stored $c 400) $tRel '400s') + " after the back office came back")
    UploadLog $tDown
  }

  W "`n=== end job"
  Cmd 'end' | Out-Null
  $d = Drain 120
  W ("   final drain " + $(if ($d -ge 0) { "${d}s" } else { 'NOT within 120s' }))
  W ("captures saved on the phone: " + (($all | ForEach-Object { $_.id }) -join ' '))
  if ($ownServer) {
    W ("server: " + (uv run --project (Join-Path $repo 'backend') python (Join-Path $repo 'tools\chaos_counts.py') (Join-Path $work 'data') $job $serverLog))
  }
} catch {
  W "ERROR: $($_.Exception.Message)"
} finally {
  try { TP POST '/proxies/bo' @{ enabled = $true } | Out-Null; foreach ($n in 'lat_down', 'lat_up', 'bw', 'rst', 'blackhole') { try { Untoxic $n } catch { } } } catch { }
  # Whole process trees: Stop-Process on `uv` left its Python server running on :8001 (and holding this
  # script's output pipe open), as chaos_run.ps1 had found before.
  if ($logcat) { & taskkill /PID $logcat.Id /T /F 2>$null | Out-Null }
  foreach ($p in $started) { & taskkill /PID $p.Id /T /F 2>$null | Out-Null }
  Adb reverse tcp:8000 tcp:8002 | Out-Null
  Write-Host "results: $out"
}
