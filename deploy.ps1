# Builds Lumen TV and installs it on the TV over ADB.
# Usage:  .\deploy.ps1 -Tv 192.168.1.50[:port]  [-Release]
param(
    [Parameter(Mandatory = $true)][string]$Tv,
    [switch]$Release
)
# adb and Gradle print progress on stderr: Windows PowerShell 5.1 would turn that into errors, so check exit codes instead.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot

function Step([string]$what, [scriptblock]$run) {
    & $run
    if ($LASTEXITCODE -ne 0) { Write-Host "ÉCHEC : $what (code $LASTEXITCODE)" -ForegroundColor Red; exit 1 }
}

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName } else { Write-Host 'JDK 17 introuvable : winget install Microsoft.OpenJDK.17' -ForegroundColor Red; exit 1 }
}
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }

Step "connexion ADB à $Tv" { cmd /c "adb connect $Tv 2>&1" }
$state = (cmd /c "adb -s $Tv get-state 2>&1")
if ($state -ne 'device') { Write-Host "La TV n'est pas prête ($state). Acceptez « Autoriser le débogage USB ? » sur la TV puis relancez." -ForegroundColor Yellow; exit 1 }

$task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
Step "compilation ($task)" { cmd /c ".\gradlew.bat --no-daemon -q $task 2>&1" }
$apk = if ($Release) { 'app\build\outputs\apk\release\app-release.apk' } else { 'app\build\outputs\apk\debug\app-debug.apk' }

# "adb install" can wait forever for an on-screen confirmation on Android 14: push, then pm install.
Step 'envoi de l''APK' { cmd /c "adb -s $Tv push $apk /data/local/tmp/lumen.apk 2>&1" }
Step 'installation' { cmd /c "adb -s $Tv shell pm install -r /data/local/tmp/lumen.apk 2>&1" }
cmd /c "adb -s $Tv shell rm /data/local/tmp/lumen.apk 2>&1" | Out-Null
Step 'lancement' { cmd /c "adb -s $Tv shell am start -n com.lumentv/.ui.MainActivity 2>&1" }
Write-Host "Lumen TV installé et lancé sur $Tv" -ForegroundColor Green
