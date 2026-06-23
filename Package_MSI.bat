@echo off
:: ─────────────────────────────────────────────────────────────────────────────
::  Package_MSI.bat  —  Build the Image & Video Viewer Windows .msi installer
::
::  Prerequisites (one-time setup):
::    1. Run Setup_WiX.bat as Administrator   (installs WiX 3.11.2)
::    2. JDK 21 must be installed             (provides jpackage)
::       Default: C:\Program Files\Java\jdk-21.0.10\
::
::  Optional (improve format support at runtime, not bundled):
::    - VLC 3.x 64-bit   https://videolan.org/
::    - ImageMagick       https://imagemagick.org/
::    - Whisper CLI       pip install openai-whisper
::    - argostranslate    pip install argostranslate
::
::  Output:  target\installer\Image-Video-Viewer-1.0.0.msi
:: ─────────────────────────────────────────────────────────────────────────────

cd /d "%~dp0"

:: ── Configuration ─────────────────────────────────────────────────────────────
set APP_NAME=Image Video Viewer
set APP_VERSION=1.0.0
set APP_VENDOR=JP
set APP_DESC=A fast image and video viewer
set MAIN_JAR=image-video-viewer-%APP_VERSION%-fat.jar

:: jpackage from JDK 21 (required — JDK 25 jpackage can't build for JDK 21 targets)
set JPACKAGE=C:\Program Files\Java\jdk-21.0.10\bin\jpackage.exe

:: Upgrade UUID — change this if you fork the project, keep it fixed for updates
set UPGRADE_UUID=5E7F1A3B-9C2D-4E6F-8A0B-1C2D3E4F5678

:: ── Auto-detect Maven (handles installs not on the double-click PATH) ─────────
set MVN=mvn
where mvn >nul 2>&1
if errorlevel 1 (
    :: Try common install locations
    for %%D in (
        "C:\Program Files\Apache\apache-maven-3.9.14\bin\mvn.cmd"
        "C:\Program Files\Apache\apache-maven-3.9.9\bin\mvn.cmd"
        "C:\Program Files\Apache Maven\bin\mvn.cmd"
        "C:\tools\maven\bin\mvn.cmd"
        "%USERPROFILE%\scoop\apps\maven\current\bin\mvn.cmd"
        "%~dp0maven\bin\mvn.cmd"
    ) do (
        if exist %%D set MVN=%%~D
    )
    :: Wildcard search under Program Files\Apache
    if "%MVN%"=="mvn" (
        for /f "delims=" %%F in ('dir /b /s "C:\Program Files\Apache\*mvn.cmd" 2^>nul') do set MVN=%%F
    )
)

:: ── Auto-detect WiX (checks PATH and known install locations) ─────────────────
set CANDLE=candle
where candle >nul 2>&1
if errorlevel 1 (
    for %%D in (
        "C:\WiX311\candle.exe"
        "C:\Program Files (x86)\WiX Toolset v3.11\bin\candle.exe"
        "C:\Program Files\WiX Toolset v3.11\bin\candle.exe"
    ) do (
        if exist %%D (
            set CANDLE=%%~D
            :: Add the WiX folder to PATH for this session so light.exe is found too
            for %%P in (%%D) do set PATH=%%~dpP;%PATH%
        )
    )
)

:: ── Preflight checks ──────────────────────────────────────────────────────────
echo.
echo === Image ^& Video Viewer — MSI Packager ===
echo.

"%MVN%" -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Maven not found.
    echo        Checked PATH and common install locations.
    echo        Install Maven from https://maven.apache.org/ or run Setup_Maven.bat.
    pause & exit /b 1
)

if not exist "%JPACKAGE%" (
    echo ERROR: jpackage not found at:
    echo        %JPACKAGE%
    echo        Install JDK 21 from https://adoptium.net/ or update JPACKAGE path above.
    pause & exit /b 1
)

"%CANDLE%" -? >nul 2>&1
if errorlevel 1 (
    echo ERROR: WiX candle.exe not found.
    echo        Run Setup_WiX.bat as Administrator first.
    pause & exit /b 1
)

:: ── Step 1: Build fat JAR + stage JavaFX win JARs ────────────────────────────
echo [1/4] Building fat JAR and staging JavaFX modules...
echo.

call "%MVN%" package -DskipTests -q
if errorlevel 1 (
    echo.
    echo ERROR: Maven build failed. Check output above.
    pause & exit /b 1
)

if not exist "target\%MAIN_JAR%" (
    echo ERROR: Fat JAR not found: target\%MAIN_JAR%
    pause & exit /b 1
)

if not exist "target\javafx-mods\javafx-controls.jar" (
    echo ERROR: JavaFX win JARs not found in target\javafx-mods\
    echo        The maven-dependency-plugin should have copied them during package.
    pause & exit /b 1
)

echo     Fat JAR:      target\%MAIN_JAR%
echo     JavaFX mods:  target\javafx-mods\

:: ── Step 2: Stage app JARs ───────────────────────────────────────────────────
echo.
echo [2/4] Staging application files...

if exist "target\app" rmdir /s /q "target\app"
mkdir "target\app"
copy /y "target\%MAIN_JAR%" "target\app\" >nul

:: ── Step 2b: Bundle VLC native libraries ──────────────────────────────────────
echo.
echo [2b/4] Bundling VLC...

set VLC_SRC=
if exist "C:\Program Files\VideoLAN\VLC\libvlc.dll"       set "VLC_SRC=C:\Program Files\VideoLAN\VLC"
if exist "C:\Program Files (x86)\VideoLAN\VLC\libvlc.dll" set "VLC_SRC=C:\Program Files (x86)\VideoLAN\VLC"

if "%VLC_SRC%"=="" (
    echo.
    echo  WARNING: VLC not found on this build machine.
    echo           Install VLC 3.x 64-bit from https://videolan.org/ to enable bundling.
    echo           Continuing without bundled VLC -- users must install VLC themselves.
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

:: ── Step 3: Create output directory ──────────────────────────────────────────
if exist "target\installer" rmdir /s /q "target\installer"
mkdir "target\installer"

:: ── Step 4: Run jpackage ─────────────────────────────────────────────────────
echo.
echo [3/4] Running jpackage (VLC bundled in app\vlc\)...
echo.

"%JPACKAGE%" ^
  --type msi ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --description "%APP_DESC%" ^
  --vendor "%APP_VENDOR%" ^
  --icon "src\main\resources\com\imageviewer\app_icon.ico" ^
  --input "target\app" ^
  --main-jar %MAIN_JAR% ^
  --module-path "target\javafx-mods" ^
  --add-modules javafx.controls,javafx.graphics,javafx.media,java.desktop,java.xml,jdk.unsupported ^
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.util=ALL-UNNAMED" ^
  --win-menu ^
  --win-shortcut ^
  --win-dir-chooser ^
  --win-upgrade-uuid %UPGRADE_UUID% ^
  --dest "target\installer"

if errorlevel 1 (
    echo.
    echo ERROR: jpackage failed. Check output above.
    pause & exit /b 1
)

:: ── Done ─────────────────────────────────────────────────────────────────────
echo.
echo [4/4] Done!
echo.
echo  Output: target\installer\Image-Video-Viewer-%APP_VERSION%.msi
echo.
echo  The installer will:
echo    - Install to C:\Program Files\Image Video Viewer\  (user can change)
echo    - Add a Start Menu shortcut
echo    - Add a Desktop shortcut
echo    - Register for Add/Remove Programs (supports clean uninstall)
echo.
echo  NOTE: VLC is bundled inside the installer (app\vlc\).
echo        ImageMagick and Whisper are optional — users install those separately.
echo.

:: Open the installer folder in Explorer
explorer "target\installer"

pause
