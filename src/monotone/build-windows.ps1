# Monotone Windows build - installs what's missing, then builds the EXE.
# Safe to re-run; it skips anything already installed.

$ErrorActionPreference = 'Continue'

# Re-launch as admin (needed for installers and Flutter plugin symlinks)
$me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

Set-Location $PSScriptRoot
function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "`nBUILD FAILED: $m" -ForegroundColor Red; Read-Host "Press Enter to close"; exit 1 }
function RefreshPath {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User')
}
$wingetArgs = @('--accept-source-agreements', '--accept-package-agreements', '-e')

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Fail "winget not found. Install 'App Installer' from the Microsoft Store, then run this again."
}

# 1. Git
Step "Checking Git"
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    winget install --id Git.Git @wingetArgs --silent
    RefreshPath
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail "Git install didn't work." }
}

# 2. Visual Studio C++ build tools
Step "Checking Visual Studio C++ build tools"
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vs = $null
if (Test-Path $vswhere) {
    $vs = & $vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
}
if (-not $vs) {
    Write-Host "Installing Visual Studio Build Tools (C++). This is a few GB and can take 10-20 minutes..."
    winget install --id Microsoft.VisualStudio.2022.BuildTools @wingetArgs `
        --override "--wait --passive --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
    if (Test-Path $vswhere) {
        $vs = & $vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    }
    if (-not $vs) { Fail "C++ build tools didn't install. Reboot and run this again." }
}

# 3. Flutter
Step "Checking Flutter"
$flutterDir = Join-Path $env:LOCALAPPDATA 'flutter'
if (-not (Test-Path "$flutterDir\bin\flutter.bat")) {
    Write-Host "Downloading Flutter..."
    git clone --depth 1 -b stable https://github.com/flutter/flutter.git $flutterDir
    if ($LASTEXITCODE -ne 0) { Fail "Couldn't download Flutter." }
}
git config --global --add safe.directory ($flutterDir -replace '\\', '/') 2>$null
$env:Path = "$flutterDir\bin;$env:Path"
flutter --disable-analytics | Out-Null

# 4. Build
Step "Getting packages"
flutter pub get
if ($LASTEXITCODE -ne 0) { Fail "flutter pub get failed (see messages above)." }

Step "Building Monotone.exe (first build takes a few minutes)"
flutter build windows --release
if ($LASTEXITCODE -ne 0) { Fail "flutter build failed (see messages above)." }

$src = Join-Path $PSScriptRoot 'build\windows\x64\runner\Release'
$out = Join-Path $PSScriptRoot 'Monotone-Windows'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
Copy-Item $src $out -Recurse

Write-Host "`nDONE. Your app is in: $out" -ForegroundColor Green
Write-Host "Run Monotone.exe from that folder (keep the DLLs and data folder next to it)."
explorer $out
Read-Host "Press Enter to close"
