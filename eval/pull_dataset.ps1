# Copies every dataset capture from the phone (debug build, dataset mode ON) into eval/raw/.
# Usage (repo root):  powershell -ExecutionPolicy Bypass -File eval\pull_dataset.ps1
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$dest = Join-Path $PSScriptRoot 'raw'
New-Item -ItemType Directory -Force $dest | Out-Null
& $adb pull /sdcard/Android/data/app.fieldcapture/files/dataset/. $dest
$n = (Get-ChildItem -Recurse -Filter capture.json $dest).Count
Write-Output "eval/raw now holds $n captures"
