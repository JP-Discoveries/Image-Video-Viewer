$base = $PSScriptRoot

# Update the fat JAR in the staging folder
Copy-Item "$base\target\image-video-viewer-1.0.0-fat.jar" "$base\target\app\image-video-viewer-1.0.0-fat.jar" -Force
Write-Host 'JAR updated'

# Remove old output
if (Test-Path "$base\target\Image Video Viewer") {
    Remove-Item -Recurse -Force "$base\target\Image Video Viewer"
}

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
    Write-Host 'Done! Output: target\Image Video Viewer\Image Video Viewer.exe'
} else {
    Write-Host "jpackage failed with exit code $LASTEXITCODE"
}
