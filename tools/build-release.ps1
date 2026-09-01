$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = Join-Path $root '.gradle-user'
$env:ANDROID_USER_HOME = Join-Path $root '.gradle-user\.android'
$env:ANDROID_SDK_HOME = Join-Path $root '.gradle-user'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat :app:assembleRelease -x :ui:renderer:generateReleaseLintModel
exit $LASTEXITCODE
