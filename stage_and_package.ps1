$base = $PSScriptRoot

# Stage app folder
New-Item -ItemType Directory -Force "$base\target\app" | Out-Null
Copy-Item "$base\target\image-video-viewer-1.0.0-fat.jar" "$base\target\app\image-video-viewer-1.0.0-fat.jar" -Force
Write-Host 'JAR staged'

# Bundle VLC
$vlcSrc = $null
if (Test-Path 'C:\Program Files\VideoLAN\VLC\libvlc.dll') { $vlcSrc = 'C:\Program Files\VideoLAN\VLC' }
elseif (Test-Path 'C:\Program Files\VLC\libvlc.dll') { $vlcSrc = 'C:\Program Files\VLC' }
if ($vlcSrc) {
    New-Item -ItemType Directory -Force "$base\target\app\vlc" | Out-Null
    Copy-Item "$vlcSrc\libvlc.dll" "$base\target\app\vlc\" -Force
    Copy-Item "$vlcSrc\libvlccore.dll" "$base\target\app\vlc\" -Force
    Copy-Item -Recurse -Force "$vlcSrc\plugins" "$base\target\app\vlc\plugins"
    Write-Host "VLC bundled from $vlcSrc"
} else { Write-Host 'VLC not found - skipping' }

# Bundle ffmpeg
$ffSrc = $null
if (Test-Path 'C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin\ffmpeg.exe') { $ffSrc = 'C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin' }
elseif (Test-Path 'C:\ffmpeg\bin\ffmpeg.exe') { $ffSrc = 'C:\ffmpeg\bin' }
if ($ffSrc) {
    New-Item -ItemType Directory -Force "$base\target\app\ffmpeg" | Out-Null
    Copy-Item "$ffSrc\ffmpeg.exe" "$base\target\app\ffmpeg\" -Force
    Copy-Item "$ffSrc\ffprobe.exe" "$base\target\app\ffmpeg\" -Force
    Write-Host "ffmpeg bundled from $ffSrc"
} else { Write-Host 'ffmpeg not found - skipping' }

# Bundle ImageMagick
$imSrc = $null
Get-ChildItem 'C:\Program Files' -Filter 'ImageMagick*' -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    if (Test-Path "$($_.FullName)\magick.exe") { $imSrc = $_.FullName }
}
if ($imSrc) {
    New-Item -ItemType Directory -Force "$base\target\app\imagemagick" | Out-Null
    Copy-Item "$imSrc\magick.exe" "$base\target\app\imagemagick\" -Force
    Copy-Item "$imSrc\*.dll" "$base\target\app\imagemagick\" -Force
    Copy-Item "$imSrc\*.xml" "$base\target\app\imagemagick\" -Force -ErrorAction SilentlyContinue
    if (Test-Path "$imSrc\modules") { Copy-Item -Recurse -Force "$imSrc\modules" "$base\target\app\imagemagick\modules" }
    Write-Host "ImageMagick bundled from $imSrc"
} else { Write-Host 'ImageMagick not found - skipping' }

Write-Host ''
Write-Host 'Running jpackage...'

$jpackage = 'C:\Program Files\Java\jdk-21.0.10\bin\jpackage.exe'

& $jpackage `
  --type app-image `
  --name 'Image Video Viewer' `
  --app-version '1.0.0' `
  --description 'A fast image and video viewer' `
  --vendor 'JP' `
  --icon "$base\src\main\resources\com\imageviewer\app_icon.ico" `
  --input "$base\target\app" `
  --main-jar 'image-video-viewer-1.0.0-fat.jar' `
  --module-path "$base\target\javafx-mods" `
  --add-modules 'javafx.controls,javafx.graphics,javafx.media,java.desktop,java.xml,java.logging,jdk.unsupported' `
  --java-options '--add-opens=java.base/java.lang=ALL-UNNAMED' `
  --java-options '--add-opens=java.base/java.util=ALL-UNNAMED' `
  --dest "$base\target"

if ($LASTEXITCODE -eq 0) {
    Write-Host ''
    Write-Host 'Done! Output: target\Image Video Viewer\Image Video Viewer.exe'
} else {
    Write-Host "jpackage failed with exit code $LASTEXITCODE"
}
