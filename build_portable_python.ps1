# Builds a self-contained, relocatable Python runtime under target\app\python_env
# using the python.org Windows *embeddable* distribution (no absolute paths, no
# username baked in), then installs the Whisper + Argos Translate stack into it
# and pre-downloads the Whisper "base" model.
#
# Unlike a venv, the embeddable build ships its own python3xx.dll + zipped stdlib,
# so it runs on a machine with no Python installed.
#
# Run AFTER stage_and_package.ps1 has created target\app. Re-run jpackage after
# this (stage_and_package / repackage_only) to fold python_env into the app-image.

$ErrorActionPreference = 'Stop'

$base       = $PSScriptRoot
$pyVersion  = '3.12.10'
$pyTag      = 'python312'
$envDir     = Join-Path $base 'target\app\python_env'
$models     = Join-Path $base 'target\app\whisper_models'
$whisperSize = 'base'

$embedUrl = "https://www.python.org/ftp/python/$pyVersion/python-$pyVersion-embed-amd64.zip"
$embedZip = Join-Path $env:TEMP "python-$pyVersion-embed-amd64.zip"
$getPip   = Join-Path $env:TEMP 'get-pip.py'

Write-Host "=== Portable Python build (embeddable $pyVersion) ==="

# 1. Download + extract the embeddable distribution -----------------------------
if (-not (Test-Path $embedZip)) {
    Write-Host "Downloading $embedUrl"
    Invoke-WebRequest -Uri $embedUrl -OutFile $embedZip -UseBasicParsing
}
if (Test-Path $envDir) { Remove-Item -Recurse -Force $envDir }
New-Item -ItemType Directory -Force $envDir | Out-Null
Expand-Archive -Path $embedZip -DestinationPath $envDir -Force
Write-Host "Extracted to $envDir"

# 2. Enable site-packages so pip + installed packages are importable ------------
$pth = Join-Path $envDir "$pyTag._pth"
@"
$pyTag.zip
.
Lib\site-packages
import site
"@ | Set-Content -Path $pth -Encoding ascii
Write-Host "Wrote $pth"

$py = Join-Path $envDir 'python.exe'

# 3. Bootstrap pip --------------------------------------------------------------
Write-Host 'Bootstrapping pip...'
Invoke-WebRequest -Uri 'https://bootstrap.pypa.io/get-pip.py' -OutFile $getPip -UseBasicParsing
& $py $getPip --no-warn-script-location --no-cache-dir
if ($LASTEXITCODE -ne 0) { throw 'get-pip failed' }

# 4. Install packages (CPU-only torch to avoid the multi-GB CUDA wheel;
#    torch is only used by argostranslate -> stanza for sentence segmentation).
Write-Host 'Installing CPU torch...'
& $py -m pip install --no-warn-script-location --no-cache-dir --no-compile `
    torch --index-url https://download.pytorch.org/whl/cpu
if ($LASTEXITCODE -ne 0) { throw 'torch install failed' }

Write-Host 'Installing faster-whisper, argostranslate, langdetect...'
& $py -m pip install --no-warn-script-location --no-cache-dir --no-compile `
    faster-whisper argostranslate langdetect
if ($LASTEXITCODE -ne 0) { throw 'package install failed' }

# 5. Pre-download the Whisper model into the bundled HF cache -------------------
Write-Host "Pre-downloading Whisper '$whisperSize' model..."
New-Item -ItemType Directory -Force $models | Out-Null
& $py -c "from faster_whisper import WhisperModel; WhisperModel('$whisperSize', download_root=r'$models'); print('Model ready.')"
if ($LASTEXITCODE -ne 0) { Write-Host 'WARNING: model pre-download failed - it will download on first use' }

# 6. Strip build-path artifacts ------------------------------------------------
#    - Scripts\*.exe console shims embed the absolute build path; the app calls
#      python.exe directly and never uses them.
#    - .pyc files embed source paths (co_filename) from this machine; delete so
#      Python recompiles cleanly on the user's machine.
$scripts = Join-Path $envDir 'Scripts'
if (Test-Path $scripts) { Remove-Item -Recurse -Force $scripts }
Get-ChildItem $envDir -Recurse -Directory -Filter '__pycache__' -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force
Get-ChildItem $envDir -Recurse -File -Filter '*.pyc' -ErrorAction SilentlyContinue |
    Remove-Item -Force

# 7. Verify no username / build path leaked ------------------------------------
Write-Host 'Scanning for leaked build paths...'
$leaks = Get-ChildItem $envDir -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Length -lt 1MB -and $_.Extension -in '.cfg','.txt','.py','.json','.ini','.pth','._pth','.toml','' } |
    Where-Object { Select-String -Path $_.FullName -Pattern ([regex]::Escape($env:USERNAME)) -SimpleMatch -Quiet -ErrorAction SilentlyContinue }
if ($leaks) {
    Write-Host "WARNING: username found in:"
    $leaks | ForEach-Object { Write-Host "  $($_.FullName)" }
} else {
    Write-Host 'OK - no username references in text files.'
}

$sizeMB = [math]::Round((Get-ChildItem $envDir -Recurse -File | Measure-Object Length -Sum).Sum/1MB,0)
Write-Host "=== Done. python_env = $sizeMB MB ==="
