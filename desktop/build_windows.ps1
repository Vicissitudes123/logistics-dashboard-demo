param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:PYTHONUTF8 = '1'
$env:PYTHONPATH = $PSScriptRoot
if (-not $SkipInstall) {
    python -m pip install --disable-pip-version-check -r requirements-build.txt
    if ($LASTEXITCODE -ne 0) { throw 'Pinned Python dependency installation failed' }
}
python scripts/prepare_runtime.py
if ($LASTEXITCODE -ne 0) { throw 'Native runtime preparation or Java parser test failed' }
$env:QT_QPA_PLATFORM = 'offscreen'
python scripts/run_tests.py
if ($LASTEXITCODE -ne 0) { throw 'Desktop tests failed' }
python -m PyInstaller --noconfirm --clean AnimeShelf.spec
if ($LASTEXITCODE -ne 0) { throw 'Windows executable build failed' }
python scripts/native_smoke.py --runtime-root dist/AnimeShelf/_internal
if ($LASTEXITCODE -ne 0) { throw 'Packaged native media smoke test failed' }
$env:QT_QPA_PLATFORM = 'windows'
python tests/native_widget_smoke.py --runtime-root dist/AnimeShelf/_internal
if ($LASTEXITCODE -ne 0) { throw 'Production Windows player widget smoke test failed' }
$env:ANIME_SHELF_DATA_DIR = Join-Path $PSScriptRoot 'reports/packaged-smoke-data'
$report = Join-Path $PSScriptRoot 'reports/packaged-ui-smoke.json'
$exe = Join-Path $PSScriptRoot 'dist/AnimeShelf/AnimeShelf.exe'
$process = Start-Process -FilePath $exe -ArgumentList @('--smoke-test', ('"' + $report + '"')) -PassThru
if (-not $process.WaitForExit(120000)) {
    $process.Kill()
    throw 'Packaged application did not complete UI smoke within two minutes'
}
if ($process.ExitCode -ne 0 -or -not (Test-Path $report)) { throw 'Packaged application UI smoke failed or produced no report' }
$signature = Get-AuthenticodeSignature $exe
$subject = $null
if ($signature.SignerCertificate) { $subject = $signature.SignerCertificate.Subject }
@{status=[string]$signature.Status; subject=$subject} | ConvertTo-Json | Set-Content -Encoding utf8 reports/authenticode.json
python scripts/package_windows.py
if ($LASTEXITCODE -ne 0) { throw 'Portable package verification failed' }
Write-Output 'Windows package built, tested and validated.'
