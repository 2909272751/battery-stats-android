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
    .\gradlew.bat assembleClassicRelease assembleMaterialRelease
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle assembleClassicRelease assembleMaterialRelease
} else {
    Write-Host "Gradle not found." -ForegroundColor Yellow
    exit 1
}
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$version = Select-String -Path (Join-Path $root "app\build.gradle") -Pattern 'versionName\s+"([^"]+)"' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
if (-not $version) { $version = "dev" }
$outputs = @(
    @{ Flavor = "classic"; Source = "app\build\outputs\apk\classic\release\app-classic-release.apk"; Name = "battery-stats-v$version-classic-release-signed.apk" },
    @{ Flavor = "material"; Source = "app\build\outputs\apk\material\release\app-material-release.apk"; Name = "battery-stats-v$version-material-release-signed.apk" }
)
$generated = $false
foreach ($item in $outputs) {
    $apk = Join-Path $root $item.Source
    $finalApk = Join-Path $dist $item.Name
    if (Test-Path $apk) {
        Copy-Item -LiteralPath $apk -Destination $finalApk -Force
        Write-Host "APK generated: $finalApk" -ForegroundColor Green
        $generated = $true
    }
}
if (-not $generated) {
    Write-Host "Build finished, but APK outputs were not found." -ForegroundColor Yellow
}
