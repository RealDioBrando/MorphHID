param(
    [string]$AdbPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [int]$DurationSeconds = 120,
    [int]$PollMilliseconds = 500
)
$ErrorActionPreference = 'Stop'
$positiveText = @('Pair', 'PAIR', 'Allow', 'Allow anyway', 'OK', 'Okay', 'Yes', 'Confirm', 'Accept', 'Continue installing', 'Install')
$deadline = [DateTime]::UtcNow.AddSeconds($DurationSeconds)
$remote = '/sdcard/morphhid-auto-prompt.xml'
Write-Host "Watching Android pairing/permission prompts for $DurationSeconds seconds..."
while ([DateTime]::UtcNow -lt $deadline) {
    & $AdbPath shell uiautomator dump $remote *> $null
    $raw = (& $AdbPath shell cat $remote) -join "`n"
    if ($raw -and $raw.Trim().StartsWith('<?xml')) {
        try {
            [xml]$ui = $raw
            $candidates = @($ui.hierarchy.node.node |
                Where-Object {
                    $_.clickable -eq 'true' -and
                    $_.enabled -eq 'true' -and
                    ($positiveText -contains $_.text -or $positiveText -contains $_.'content-desc')
                })
            foreach ($node in $candidates) {
                if ($node.text -match 'Deny|Cancel|No' -or $node.'content-desc' -match 'Deny|Cancel|No') { continue }
                if ($node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
                    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
                    $label = if ($node.text) { $node.text } else { $node.'content-desc' }
                    Write-Host "Clicking '$label' at $([int]$x),$([int]$y)"
                    & $AdbPath shell input tap ([int]$x) ([int]$y)
                    Start-Sleep -Milliseconds 700
                }
            }
        } catch {
            # UI XML can be unavailable for a moment while the screen changes.
        }
    }
    Start-Sleep -Milliseconds $PollMilliseconds
}
Write-Host 'Prompt watcher finished.'
