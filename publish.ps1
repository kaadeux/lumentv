# Publishes a new Lumen TV version to the public releases repository (APK + version.json only).
# The channel follows the current git branch of this project:
#   branch dev  -> channel dev  (your TV at home, versionName "x.y.z-dev")
#   branch main -> channel main (the family's TVs)
# Usage:  .\publish.ps1 -Notes "Ce qui change"     (after raising versionCode / versionName in app\build.gradle.kts)
# Needs in local.properties: lumen.signing, lumen.updateUrl, lumen.accessCode, lumen.releasesDir
param([string]$Notes = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# git writes its progress to stderr, which Windows PowerShell 5.1 would turn into a terminating error.
function Run-Git([string]$what) {
    $ErrorActionPreference = "Continue"
    $out = & git.exe @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git : échec ($what) : $out" }
    $out
}

$props = @{}
Get-Content local.properties | Where-Object { $_ -match '^\s*([\w.]+)\s*=\s*(.*)$' } | ForEach-Object { $props[$Matches[1]] = $Matches[2] -replace '\\:', ':' -replace '\\\\', '\' }
foreach ($k in 'lumen.signing', 'lumen.updateUrl', 'lumen.accessCode', 'lumen.releasesDir') {
    if (-not $props[$k]) { throw "local.properties : '$k' manquant." }
}
$releases = $props['lumen.releasesDir']
if (-not (Test-Path (Join-Path $releases '.git'))) { throw "Le dossier $releases n'est pas un clone git du dépôt public." }

$channel = (Run-Git "branche" rev-parse --abbrev-ref HEAD | Out-String).Trim()
if ($channel -ne 'main' -and $channel -ne 'dev') { throw "Publiez depuis la branche dev ou main (branche actuelle : $channel)." }
if ((Run-Git "état" status --porcelain | Out-String).Trim()) { throw "Des modifications ne sont pas commitées : commitez-les d'abord, pour que chaque version publiée corresponde à un commit." }

# The releases repository has one branch per channel.
Run-Git "récupération" -C $releases fetch -q origin | Out-Null
$remote = (Run-Git "branches distantes" -C $releases branch -r | Out-String)
if ($remote -match "origin/$channel\b") { Run-Git "checkout" -C $releases checkout -q -B $channel "origin/$channel" | Out-Null }
else { Run-Git "checkout" -C $releases checkout -q -B $channel origin/main | Out-Null }

$gradle = Get-Content app\build.gradle.kts -Raw
$code = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if ($channel -eq 'dev') { $name = "$name-dev" }
$jsonPath = Join-Path $releases 'version.json'
if (Test-Path $jsonPath) {
    $published = (Get-Content $jsonPath -Raw | ConvertFrom-Json).versionCode
    if ($code -le $published) { throw "versionCode $code doit être supérieur à la version publiée sur $channel ($published) : augmentez-le dans app\build.gradle.kts." }
}

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:/Program Files/Microsoft/jdk-17.0.21.21-hotspot" }
& .\gradlew.bat testDebugUnitTest assembleRelease "-PlumenChannel=$channel" -q --console=plain
if ($LASTEXITCODE -ne 0) { throw "Échec du build ou des tests." }

$apk = "app\build\outputs\apk\release\app-release.apk"
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
$cert = & "$($bt.FullName)\apksigner.bat" verify --print-certs $apk 2>$null | Select-Object -First 1
if ($cert -notmatch 'CN=Lumen TV') { throw "L'APK n'est pas signé avec la clé Lumen TV ($cert)." }

Copy-Item $apk (Join-Path $releases 'LumenTV.apk') -Force
$sha = (Get-FileHash (Join-Path $releases 'LumenTV.apk') -Algorithm SHA256).Hash.ToLower()
$apkUrl = $props['lumen.updateUrl'] -replace '/main/', "/$channel/" -replace 'version\.json$', 'LumenTV.apk'
$json = [ordered]@{ versionCode = $code; versionName = $name; apk = $apkUrl; sha256 = $sha; notes = $Notes } | ConvertTo-Json
[IO.File]::WriteAllText($jsonPath, $json, (New-Object System.Text.UTF8Encoding $false))

Run-Git "ajout" -C $releases add LumenTV.apk version.json | Out-Null
Run-Git "commit" -C $releases commit -q -m "Lumen TV $name" | Out-Null
Run-Git "envoi" -C $releases push -q -u origin $channel | Out-Null
# Marks the source commit of each published version, to find it again (or roll back).
Run-Git "tag" tag -f "v$name" | Out-Null
Run-Git "envoi du tag" push -q -f origin "v$name" | Out-Null
Write-Host "Lumen TV $name publiée sur le canal $channel. Les appareils de ce canal la proposeront au prochain démarrage."
