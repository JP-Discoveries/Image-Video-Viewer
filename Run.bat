@echo off
cd /d "%~dp0"

set JAVA_HOME=C:\Program Files\Java\jdk-21.0.10
set PATH=C:\Program Files\Apache\apache-maven-3.9.14\bin;%JAVA_HOME%\bin;%PATH%

set LOG=%~dp0run_log.txt

echo === Image ^& Video Viewer (JavaFX) === > "%LOG%"
echo Started: %DATE% %TIME% >> "%LOG%"
echo JAVA_HOME: %JAVA_HOME% >> "%LOG%"
echo. >> "%LOG%"

echo === Image ^& Video Viewer (JavaFX) ===
echo Logging to: %LOG%
echo.

mvn javafx:run >> "%LOG%" 2>&1
set EXIT_CODE=%ERRORLEVEL%

echo. >> "%LOG%"
echo === Done (exit code: %EXIT_CODE%) at %TIME% === >> "%LOG%"

echo.
if %EXIT_CODE% NEQ 0 (
    echo ERROR: Maven exited with code %EXIT_CODE%
    echo.
    echo Last 30 lines of log:
    echo ----------------------------------------
    powershell -Command "Get-Content '%LOG%' -Tail 30"
    echo ----------------------------------------
    echo.
    echo Full log saved to: %LOG%
) else (
    echo Done successfully. Log: %LOG%
)
echo.
pause
