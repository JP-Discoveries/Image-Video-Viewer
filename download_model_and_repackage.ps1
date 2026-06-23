$base = $PSScriptRoot

Write-Host '[2f] Pre-downloading Whisper small model (~244 MB)...'
New-Item -ItemType Directory -Force "$base\target\app\whisper_models" | Out-Null

$modelPath = "$base\target\app\whisper_models" -replace '\\', '/'
& "$base\target\app\python_env\Scripts\python.exe" -c "from faster_whisper import WhisperModel; WhisperModel('small', download_root='$modelPath'); print('Model ready.')"

if ($LASTEXITCODE -ne 0) {
    Write-Host 'WARNING: Whisper model download failed - it will download on first use'
} else {
    Write-Host 'Whisper small model bundled.'
}

Write-Host ''
Write-Host 'Re-running jpackage...'

$jpackage = 'C:\Program Files\Java\jdk-21.0.10\bin\jpackage.exe'

if (Test-Path "$base\target\Image Video Viewer") {
    Remove-Item -Recurse -Force "$base\target\Image Video Viewer"
}

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
