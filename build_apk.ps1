$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$toolsRoot = Join-Path (Split-Path -Parent $root) ".android_build_tools"
$localJdk = Join-Path $toolsRoot "jdk17\jdk-17.0.19+10"
$localSdk = Join-Path $toolsRoot "android-sdk"
$localGradle = Join-Path $toolsRoot "gradle\gradle-8.7\bin"

if (-not $env:JAVA_HOME -and (Test-Path $localJdk)) {
    $env:JAVA_HOME = $localJdk
}

if ((Test-Path $localSdk) -and -not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $env:ANDROID_HOME = $localSdk
    $env:ANDROID_SDK_ROOT = $localSdk
}

if (Test-Path $localGradle) {
    $env:PATH = "$localGradle;$env:PATH"
}

if ($env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

if (Test-Path ".\gradlew.bat") {
    .\gradlew.bat assembleRelease
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle assembleRelease
} else {
    Write-Host "Gradle not found." -ForegroundColor Yellow
    exit 1
}
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$version = Select-String -Path (Join-Path $root "app\build.gradle") -Pattern 'versionName\s+"([^"]+)"' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
if (-not $version) { $version = "dev" }
$finalApk = Join-Path $dist "battery-stats-v$version-release-signed.apk"
if (Test-Path $apk) {
    Copy-Item -LiteralPath $apk -Destination $finalApk -Force
    Write-Host "APK generated: $finalApk" -ForegroundColor Green
} else {
    Write-Host "Build finished, but APK output was not found." -ForegroundColor Yellow
}
