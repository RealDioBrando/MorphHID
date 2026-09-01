param(
    [string]$Tag = 'v0.1.0',
    [string]$Name = 'MorphHID v0.1.0',
    [string]$Body = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$repo = 'RealDioBrando/MorphHID'

if (-not $Body) {
    $Body = "Initial release of MorphHID.`n`n- Android-to-Bluetooth HID Device with dynamic config-driven profiles.`n- Includes a laptop-style keyboard + touchpad profile.`n- Signed release APK (debug signing key)."
}

$credInput = "protocol=https`nhost=github.com`n"
$cred = $credInput | git credential fill 2>$null
$token = ($cred | Select-String '^password=' | ForEach-Object { $_.ToString().Substring(9) })
if (-not $token) { throw 'Could not read a GitHub credential from Windows Credential Manager.' }

$headers = @{
    Authorization = "Bearer $token"
    'User-Agent' = 'MorphHID-Release'
    Accept = 'application/vnd.github+json'
}

try {
    $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$Tag" -Headers $headers -Method Get
} catch {
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode.value__ -eq 404) { $existing = $null } else { throw }
}

if ($existing) {
    Write-Host "Release $Tag already exists: $($existing.html_url)"
} else {
    $payload = @{
        tag_name = $Tag
        target_commitish = 'main'
        name = $Name
        body = $Body
        draft = $false
        prerelease = $false
    } | ConvertTo-Json

    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers -Method Post -ContentType 'application/json' -Body $payload
    Write-Host "Created release $Tag : $($release.html_url)"
    Write-Host "Next: run .\tools\github-upload-asset.ps1 -Tag $Tag"
}
