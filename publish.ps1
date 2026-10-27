# Publishes a new Lumen TV version to the public releases repository (APK + version.json only).
# Usage:  .\publish.ps1 -Notes "Ce qui change"     (after raising versionCode / versionName in app\build.gradle.kts)
# Needs in local.properties: lumen.signing, lumen.updateUrl, lumen.accessCode, lumen.releasesDir
param([string]$Notes = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$props = @{}
Get-Content local.properties | Where-Object { $_ -match '^\s*([\w.]+)\s*=\s*(.*)$' } | ForEach-Object { $props[$Matches[1]] = $Matches[2] -replace '\\:', ':' -replace '\\\\', '\' }
foreach ($k in 'lumen.signing', 'lumen.updateUrl', 'lumen.accessCode', 'lumen.releasesDir') {
    if (-not $props[$k]) { throw "local.properties : '$k' manquant." }
}
$releases = $props['lumen.releasesDir']
if (-not (Test-Path (Join-Path $releases '.git'))) { throw "Le dossier $releases n'est pas un clone git du dépôt public." }

$gradle = Get-Content app\build.gradle.kts -Raw
$code = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$jsonPath = Join-Path $releases 'version.json'
if (Test-Path $jsonPath) {
    $published = (Get-Content $jsonPath -Raw | ConvertFrom-Json).versionCode
    if ($code -le $published) { throw "versionCode $code doit être supérieur à la version publiée ($published) : augmentez-le dans app\build.gradle.kts." }
}

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:/Program Files/Microsoft/jdk-17.0.21.21-hotspot" }
& .\gradlew.bat testDebugUnitTest assembleRelease -q --console=plain
if ($LASTEXITCODE -ne 0) { throw "Échec du build ou des tests." }

$apk = "app\build\outputs\apk\release\app-release.apk"
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
$cert = & "$($bt.FullName)\apksigner.bat" verify --print-certs $apk 2>$null | Select-Object -First 1
if ($cert -notmatch 'CN=Lumen TV') { throw "L'APK n'est pas signé avec la clé Lumen TV ($cert)." }

Copy-Item $apk (Join-Path $releases 'LumenTV.apk') -Force
$sha = (Get-FileHash (Join-Path $releases 'LumenTV.apk') -Algorithm SHA256).Hash.ToLower()
$apkUrl = $props['lumen.updateUrl'] -replace 'version\.json$', 'LumenTV.apk'
$json = [ordered]@{ versionCode = $code; versionName = $name; apk = $apkUrl; sha256 = $sha; notes = $Notes } | ConvertTo-Json
[IO.File]::WriteAllText($jsonPath, $json, (New-Object System.Text.UTF8Encoding $false))

git -C $releases add LumenTV.apk version.json
git -C $releases commit -m "Lumen TV $name"
git -C $releases push
if ($LASTEXITCODE -ne 0) { throw "Échec de l'envoi vers GitHub." }
Write-Host "Lumen TV $name publiée. Les appareils la proposeront au prochain démarrage."
