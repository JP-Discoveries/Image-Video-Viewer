@echo off
cd /d "%~dp0"

set MVN_VERSION=3.9.14
set MVN_ZIP=%TEMP%\apache-maven-%MVN_VERSION%-bin.zip
set MVN_EXTRACT=%TEMP%\mvn-extract
set MVN_DIR=%~dp0maven

echo === Downloading Apache Maven %MVN_VERSION% ===
echo (This only needs to run once)
echo.

powershell -Command "Invoke-WebRequest -Uri 'https://dlcdn.apache.org/maven/maven-3/%MVN_VERSION%/binaries/apache-maven-%MVN_VERSION%-bin.zip' -OutFile '%MVN_ZIP%' -UseBasicParsing"

if errorlevel 1 (
    echo.
    echo ERROR: Download failed. Check your internet connection.
    pause
    exit /b 1
)

echo.
echo === Extracting Maven ===

if exist "%MVN_EXTRACT%" rmdir /s /q "%MVN_EXTRACT%"
powershell -Command "Expand-Archive -Path '%MVN_ZIP%' -DestinationPath '%MVN_EXTRACT%' -Force"

if errorlevel 1 (
    echo ERROR: Extraction failed.
    pause
    exit /b 1
)

:: The zip contains a single subfolder (apache-maven-x.y.z).
:: Move it to the project's maven\ dir so maven\bin\mvn.cmd works.
if exist "%MVN_DIR%" rmdir /s /q "%MVN_DIR%"
for /f "delims=" %%D in ('dir /b /ad "%MVN_EXTRACT%"') do (
    move "%MVN_EXTRACT%\%%D" "%MVN_DIR%" >nul
)
if exist "%MVN_EXTRACT%" rmdir /s /q "%MVN_EXTRACT%"
del /q "%MVN_ZIP%" 2>nul

if not exist "%MVN_DIR%\bin\mvn.cmd" (
    echo ERROR: Maven install looks wrong - bin\mvn.cmd not found.
    pause
    exit /b 1
)

echo.
echo === Done! Maven installed to: %MVN_DIR% ===
echo You can now run Build_App.bat or Run.bat
echo.
pause
