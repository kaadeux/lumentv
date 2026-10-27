# Downloads the official VLC 3.0.21 (64-bit) and keeps only the playback engine for Lumen TV for Windows.
# The checksum is verified against the one published by VideoLAN. Run once before building the Windows app.
$ErrorActionPreference = "Stop"
$dest = Join-Path $PSScriptRoot "resources\windows\vlc"
if (Test-Path (Join-Path $dest "libvlc.dll")) { Write-Host "VLC déjà présent."; exit 0 }
$tmp = Join-Path $env:TEMP "lumentv-vlc"; New-Item -ItemType Directory -Force $tmp | Out-Null
$zip = Join-Path $tmp "vlc.zip"
Invoke-WebRequest "https://get.videolan.org/vlc/3.0.21/win64/vlc-3.0.21-win64.zip" -OutFile $zip
$expected = ((Invoke-WebRequest "https://get.videolan.org/vlc/3.0.21/win64/vlc-3.0.21-win64.zip.sha256").Content -split ' ')[0].Trim()
if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $expected) { throw "Somme de contrôle VLC incorrecte." }
Expand-Archive $zip -DestinationPath $tmp -Force
$src = Join-Path $tmp "vlc-3.0.21"
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item "$src\libvlc.dll", "$src\libvlccore.dll" $dest
Copy-Item "$src\COPYING.txt" (Join-Path $dest "COPYING-VLC.txt")
Copy-Item "$src\plugins" $dest -Recurse
foreach ($d in 'gui', 'lua', 'visualization', 'video_splitter', 'control', 'services_discovery', 'stream_out') { Remove-Item (Join-Path $dest "plugins\$d") -Recurse -Force -ErrorAction SilentlyContinue }
Write-Host "VLC prêt dans $dest"
