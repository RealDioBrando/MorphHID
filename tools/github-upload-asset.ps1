param(
    [string]$Tag = 'v0.1.0',
    [string]$ApkPath = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$repo = 'RealDioBrando/MorphHID'
if (-not $ApkPath) { $ApkPath = Join-Path $root 'app\build\outputs\apk\release\app-release.apk' }
if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }

$credInput = "protocol=https`nhost=github.com`n"
$cred = $credInput | git credential fill 2>$null
$token = ($cred | Select-String '^password=' | ForEach-Object { $_.ToString().Substring(9) })
if (-not $token) { throw 'Could not read a GitHub credential from Windows Credential Manager.' }

$headers = @{ Authorization = "Bearer $token"; 'User-Agent' = 'MorphHID-Release'; Accept = 'application/vnd.github+json' }
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$Tag" -Headers $headers -Method Get
if (-not $release) { throw "Release $Tag not found." }

$assetName = "MorphHID-$Tag.apk"
$uploadUrl = "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$([uri]::EscapeDataString($assetName))"
Write-Host "Uploading $assetName to release id $($release.id) ..."

$response = & C:\Windows\System32\curl.exe --progress-bar --max-time 1800 -L -X POST `
    -H "Authorization: Bearer $token" `
    -H "User-Agent: MorphHID-Release" `
    -H "Accept: application/vnd.github+json" `
    -H "Content-Type: application/vnd.android.package-archive" `
    --data-binary "@$ApkPath" `
    $uploadUrl
if ($LASTEXITCODE -ne 0) { throw "curl upload failed with exit code $LASTEXITCODE" }

$asset = $response | ConvertFrom-Json
if (-not $asset) { throw 'Upload returned no parseable JSON.' }
Write-Host "Release URL: $($release.html_url)"
Write-Host "Asset URL: $($asset.browser_download_url)"
Write-Host "Asset size: $($asset.size)"
