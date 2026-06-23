@echo off
:: ─────────────────────────────────────────────────────────────────────────────
::  Build a fat JAR (includes all dependencies except JavaFX platform JARs).
::
::  Output: target\image-video-viewer-1.0.0-fat.jar
::
::  NOTE: JavaFX requires the JVM to be started with its modules on the module
::  path.  The fat-JAR approach therefore needs a small launcher.
::  Recommended: use "Run.bat" (mvn javafx:run) for development.
::  For distribution, use "jlink" or GraalVM native-image instead.
:: ─────────────────────────────────────────────────────────────────────────────

cd /d "%~dp0"

where java >nul 2>&1 || (echo Java not found & pause & exit /b 1)
where mvn  >nul 2>&1 || (echo Maven not found & pause & exit /b 1)

echo.
echo === Building fat JAR ===
echo.

mvn package -DskipTests

if errorlevel 1 (
    echo.
    echo === Build FAILED ===
    pause
    exit /b 1
)

echo.
echo === Build complete ===
echo.
echo Output: target\image-video-viewer-1.0.0-fat.jar
echo.
echo To run (ensure JavaFX is on module-path or use Run.bat):
echo   mvn javafx:run
echo.
pause
