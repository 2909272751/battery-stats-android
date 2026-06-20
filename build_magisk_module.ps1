$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$module = Join-Path $root "magisk_module"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$versionLine = Select-String -Path (Join-Path $module "module.prop") -Pattern '^version=(.+)$' | Select-Object -First 1
$version = if ($versionLine) { $versionLine.Matches[0].Groups[1].Value.Trim() } else { "dev" }
$zip = Join-Path $dist "battery-stats-v$version-magisk-module.zip"
if (Test-Path $zip) {
    Remove-Item -LiteralPath $zip -Force
}

$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) {
    $env:BATTERY_STATS_MODULE_DIR = $module
    $env:BATTERY_STATS_ZIP_PATH = $zip
    python -c "import os, zipfile; module=os.environ['BATTERY_STATS_MODULE_DIR']; zip_path=os.environ['BATTERY_STATS_ZIP_PATH']; z=zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED); [z.writestr((lambda p,arc: (lambda info: (setattr(info,'external_attr',(0o100755 if os.path.basename(p).endswith('.sh') else 0o100644)<<16) or info))(zipfile.ZipInfo.from_file(p,arc)))(os.path.join(r,n), os.path.relpath(os.path.join(r,n), module).replace(os.sep,'/')), open(os.path.join(r,n),'rb').read(), zipfile.ZIP_DEFLATED) for r,_,fs in os.walk(module) for n in fs]; z.close()"
    if ($LASTEXITCODE -ne 0) {
        throw "Python zip packaging failed."
    }
} else {
    Compress-Archive -Path (Join-Path $module "*") -DestinationPath $zip -Force
}
Write-Host "Magisk module generated: $zip" -ForegroundColor Green
