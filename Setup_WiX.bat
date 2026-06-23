@echo off
:: ─────────────────────────────────────────────────────────────────────────────
::  Setup_WiX.bat  —  One-time download of WiX 3.11.2 (required by jpackage 21)
::
::  jpackage from JDK 21 requires WiX 3.x to build .msi files.
::  This script downloads the WiX 3.11.2 binaries and installs them to:
::    C:\WiX311\
::  then adds that folder to the SYSTEM PATH permanently.
::
::  Run this once before running Package_MSI.bat.
::  Requires internet access and administrator privileges.
:: ─────────────────────────────────────────────────────────────────────────────

:: Require admin
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Run this script as Administrator.
    pause & exit /b 1
)

set WIX_DIR=C:\WiX311
set WIX_ZIP=%TEMP%\wix311-binaries.zip
set WIX_URL=https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip

echo.
echo === WiX 3.11.2 Setup ===
echo.

:: Check if already installed
if exist "%WIX_DIR%\candle.exe" (
    echo WiX 3.11.2 is already installed at %WIX_DIR%
    echo candle.exe: found
    echo light.exe:  found
    goto :check_path
)

echo Downloading WiX 3.11.2 binaries...
powershell -NoProfile -Command ^
    "Invoke-WebRequest -Uri '%WIX_URL%' -OutFile '%WIX_ZIP%' -UseBasicParsing"
if errorlevel 1 (
    echo ERROR: Download failed. Check your internet connection.
    pause & exit /b 1
)

echo Extracting to %WIX_DIR% ...
if not exist "%WIX_DIR%" mkdir "%WIX_DIR%"
powershell -NoProfile -Command ^
    "Expand-Archive -LiteralPath '%WIX_ZIP%' -DestinationPath '%WIX_DIR%' -Force"
if errorlevel 1 (
    echo ERROR: Extraction failed.
    pause & exit /b 1
)

del /q "%WIX_ZIP%" 2>nul

:check_path
echo.
echo Checking PATH...

:: Check if WiX_DIR is already in system PATH
echo %PATH% | findstr /i /c:"%WIX_DIR%" >nul
if %errorlevel% equ 0 (
    echo %WIX_DIR% is already on PATH.
) else (
    echo Adding %WIX_DIR% to system PATH...
    setx /M PATH "%PATH%;%WIX_DIR%"
    echo Done. You may need to open a new command window for PATH to take effect.
)

echo.
echo === WiX Setup Complete ===
echo.
echo candle.exe : %WIX_DIR%\candle.exe
echo light.exe  : %WIX_DIR%\light.exe
echo.
echo You can now run Package_MSI.bat to build the installer.
echo.
pause
