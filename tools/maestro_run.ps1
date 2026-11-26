# Runs the Maestro UI flows in android/maestro against the debug build on the connected phone.
# Usage (repo root):  powershell -ExecutionPolicy Bypass -File tools\maestro_run.ps1 [-Flow 03_rapid_taps.yaml]
# Needs Maestro CLI 2.12.1 (https://github.com/mobile-dev-inc/maestro, Apache-2.0) and Java 17+ (JAVA_HOME).
# Maestro installs its own driver app on the phone for the run.
param(
  [string]$Maestro = (Join-Path $env:LOCALAPPDATA 'fc-tools\maestro-2.12.1\maestro\bin\maestro.bat'),
  [string]$Flow = '',
  [string]$OutDir = (Join-Path $env:TEMP 'fieldcapture-maestro')
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $Maestro)) { throw "Maestro not found at $Maestro" }
$env:MAESTRO_CLI_NO_ANALYTICS = '1'
$env:MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED = 'true'
New-Item -ItemType Directory -Force $OutDir | Out-Null

# Flows start jobs with the Start job button, which turns the phone mic on. Nobody speaks during a UI
# run, so turn the mic off each time a job starts: room sound must neither trigger commands nor end up
# in the job. Only on a job start: a broadcast starts the app process, and broadcasting on a timer
# fought Maestro's own stop-then-launch (launchApp hung for 2 min).
$muter = Start-Job -ScriptBlock {
  param($adb)
  $done = @{}
  while ($true) {
    $lines = & $adb logcat -d -v epoch -t 300 -s 'FieldCapture:I' 2>$null
    foreach ($l in $lines) {
      if ($l -match '^\s*(\d+\.\d+).*\[job\] (started|resumed) (\S+)' -and -not $done.ContainsKey($matches[3])) {
        $done[$matches[3]] = $true
        & $adb shell 'am broadcast -n app.fieldcapture/.debug.DebugCommandReceiver --es cmd mic_off' 2>$null | Out-Null
      }
    }
    Start-Sleep -Milliseconds 1500
  }
} -ArgumentList $adb
try {
  $target = if ($Flow) { Join-Path $repo "android\maestro\$Flow" } else { Join-Path $repo 'android\maestro' }
  & $Maestro test $target --format junit --output (Join-Path $OutDir 'report.xml') --debug-output (Join-Path $OutDir 'debug')
  $code = $LASTEXITCODE
} finally {
  Stop-Job $muter -ErrorAction SilentlyContinue; Remove-Job $muter -Force -ErrorAction SilentlyContinue
  & $adb shell 'am broadcast -n app.fieldcapture/.debug.DebugCommandReceiver --es cmd feed_camera' 2>$null | Out-Null
}
Write-Output "JUnit report and failure screenshots: $OutDir"
exit $code
