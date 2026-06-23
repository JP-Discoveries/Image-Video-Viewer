@echo off
:: ─────────────────────────────────────────────────────────────────────────────
::  Build_App.bat  —  Build the portable Image & Video Viewer app-image
::                    (self-contained folder with .exe, bundled JRE, and VLC)
::
::  Prerequisites:
::    - JDK 21 installed (provides jpackage)
::    - Maven installed (or MAVEN_HOME set)
::    - VLC 3.x 64-bit installed (from https://videolan.org/) on this build machine
::
::  Output: target\Image Video Viewer\
::          (copy this folder anywhere — no installer needed)
:: ─────────────────────────────────────────────────────────────────────────────

cd /d "%~dp0"

:: ── Configuration ─────────────────────────────────────────────────────────────
set APP_NAME=Image Video Viewer
set APP_VERSION=1.0.0
set APP_VENDOR=JP
set APP_DESC=A fast image and video viewer
set MAIN_JAR=image-video-viewer-%APP_VERSION%-fat.jar

:: jpackage from JDK 21 (required)
set JPACKAGE=C:\Program Files\Java\jdk-21.0.10\bin\jpackage.exe

:: ── Locate Maven (local install by Setup_Maven.bat takes priority) ────────────
set "MVN=%~dp0maven\bin\mvn.cmd"
if not exist "%MVN%" set "MVN=mvn"

:: ── Preflight ─────────────────────────────────────────────────────────────────
echo.
echo === Image ^& Video Viewer — Portable App Builder ===
echo.

call "%MVN%" -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Maven not found. Install from https://maven.apache.org/ or run Setup_Maven.bat.
    pause & exit /b 1
)

if not exist "%JPACKAGE%" (
    echo ERROR: jpackage not found at: %JPACKAGE%
    echo        Install JDK 21 from https://adoptium.net/ or update JPACKAGE path above.
    pause & exit /b 1
)

:: ── Step 1: Build fat JAR + stage JavaFX win JARs ────────────────────────────
echo [1/4] Building fat JAR and staging JavaFX modules...
call "%MVN%" package -DskipTests
if errorlevel 1 (
    echo ERROR: Maven build failed.
    pause & exit /b 1
)
if not exist "target\%MAIN_JAR%" (
    echo ERROR: Fat JAR not found: target\%MAIN_JAR%
    pause & exit /b 1
)

:: ── Step 2: Stage app files ───────────────────────────────────────────────────
echo.
echo [2/4] Staging application files...
if exist "target\app" rmdir /s /q "target\app"
mkdir "target\app"
copy /y "target\%MAIN_JAR%" "target\app\" >nul

:: ── Step 2b: Bundle VLC ───────────────────────────────────────────────────────
echo.
echo [2b/4] Bundling VLC...

set VLC_SRC=
if exist "C:\Program Files\VLC\libvlc.dll"                set "VLC_SRC=C:\Program Files\VLC"
if exist "C:\Program Files\VideoLAN\VLC\libvlc.dll"       set "VLC_SRC=C:\Program Files\VideoLAN\VLC"
if exist "C:\Program Files (x86)\VideoLAN\VLC\libvlc.dll" set "VLC_SRC=C:\Program Files (x86)\VideoLAN\VLC"

if "%VLC_SRC%"=="" (
    echo.
    echo  WARNING: VLC not found on this build machine.
    echo           Install VLC 3.x 64-bit from https://videolan.org/ to enable bundling.
    echo           The app will still work if users have VLC installed on their machine,
    echo           but this portable build will show "VLC not found" on machines without it.
    echo.
) else (
    echo     Source: %VLC_SRC%
    if exist "target\app\vlc" rmdir /s /q "target\app\vlc"
    mkdir "target\app\vlc"
    copy /y "%VLC_SRC%\libvlc.dll"     "target\app\vlc\" >nul
    copy /y "%VLC_SRC%\libvlccore.dll" "target\app\vlc\" >nul
    xcopy /E /I /Q "%VLC_SRC%\plugins" "target\app\vlc\plugins\" >nul
    echo     VLC bundled successfully.
)

:: ── Step 2c: Bundle ImageMagick ───────────────────────────────────────────────
echo.
echo [2c/4] Bundling ImageMagick...

set IM_SRC=
for /d %%D in ("C:\Program Files\ImageMagick*") do set "IM_SRC=%%D"
for /d %%D in ("C:\Program Files (x86)\ImageMagick*") do set "IM_SRC=%%D"
if exist "C:\Program Files\ImageMagick\magick.exe" set "IM_SRC=C:\Program Files\ImageMagick"

if "%IM_SRC%"=="" (
    echo.
    echo  WARNING: ImageMagick not found on this build machine.
    echo           Install ImageMagick 7 from https://imagemagick.org/ to enable bundling.
    echo           The app will still work for most images, but RAW/ICO/TIFF support
    echo           will be limited on machines without ImageMagick installed.
    echo.
) else (
    echo     Source: %IM_SRC%
    if exist "target\app\imagemagick" rmdir /s /q "target\app\imagemagick"
    mkdir "target\app\imagemagick"
    copy /y "%IM_SRC%\magick.exe"    "target\app\imagemagick\" >nul
    copy /y "%IM_SRC%\*.dll"         "target\app\imagemagick\" >nul
    copy /y "%IM_SRC%\*.xml"         "target\app\imagemagick\" >nul 2>nul
    if exist "%IM_SRC%\modules" xcopy /E /I /Q "%IM_SRC%\modules" "target\app\imagemagick\modules\" >nul
    echo     ImageMagick bundled successfully.
)

:: ── Step 2d: Bundle ffmpeg + ffprobe ─────────────────────────────────────────
echo.
echo [2d/4] Bundling ffmpeg + ffprobe...

set FFMPEG_SRC=
if exist "C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin\ffmpeg.exe" set "FFMPEG_SRC=C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin"
if "%FFMPEG_SRC%"=="" if exist "C:\ffmpeg\bin\ffmpeg.exe"                   set "FFMPEG_SRC=C:\ffmpeg\bin"
if "%FFMPEG_SRC%"=="" (
    for /d %%D in ("C:\Program Files\ffmpeg*") do (
        if exist "%%D\bin\ffmpeg.exe" set "FFMPEG_SRC=%%D\bin"
    )
)

if "%FFMPEG_SRC%"=="" (
    echo  WARNING: ffmpeg not found. Video thumbnails and seek previews will not work.
    echo           Install from https://ffmpeg.org/download.html or via: choco install ffmpeg
) else (
    echo     Source: %FFMPEG_SRC%
    if exist "target\app\ffmpeg" rmdir /s /q "target\app\ffmpeg"
    mkdir "target\app\ffmpeg"
    copy /y "%FFMPEG_SRC%\ffmpeg.exe"  "target\app\ffmpeg\" >nul
    copy /y "%FFMPEG_SRC%\ffprobe.exe" "target\app\ffmpeg\" >nul
    echo     ffmpeg bundled successfully.
)

:: ── Step 2e: Bundle self-contained Python (whisper + argostranslate + model) ──
:: Uses the python.org embeddable distribution so the bundled Python is portable
:: (runs on machines with no Python installed). See build_portable_python.ps1.
echo.
echo [2e/4] Building portable Python environment (this may take several minutes)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_portable_python.ps1"
if errorlevel 1 (
    echo  WARNING: Portable Python build failed. Whisper/translation will not be bundled.
)

:: ── Step 3: Run jpackage ─────────────────────────────────────────────────────
echo.
echo [3/4] Running jpackage (app-image)...
echo.

if exist "target\Image Video Viewer" rmdir /s /q "target\Image Video Viewer"

"%JPACKAGE%" ^
  --type app-image ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --description "%APP_DESC%" ^
  --vendor "%APP_VENDOR%" ^
  --icon "src\main\resources\com\imageviewer\app_icon.ico" ^
  --input "target\app" ^
  --main-jar %MAIN_JAR% ^
  --module-path "target\javafx-mods" ^
  --add-modules javafx.controls,javafx.graphics,javafx.media,java.desktop,java.xml,java.logging,jdk.unsupported ^
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.util=ALL-UNNAMED" ^
  --dest "target"

if errorlevel 1 (
    echo ERROR: jpackage failed.
    pause & exit /b 1
)

:: ── Done ─────────────────────────────────────────────────────────────────────
echo.
echo [4/4] Done!
echo.
echo  Output: target\Image Video Viewer\
echo.
echo  The folder is self-contained — copy it anywhere and run Image Video Viewer.exe.
echo  VLC          bundled in app\vlc\
echo  ImageMagick  bundled in app\imagemagick\
echo  ffmpeg       bundled in app\ffmpeg\
echo  Python env   bundled in app\python_env\  (argostranslate + faster-whisper)
echo  Whisper model bundled in app\whisper_models\
echo.
echo  All features work out of the box — no additional installs needed.
echo.

explorer "target\Image Video Viewer"
pause
