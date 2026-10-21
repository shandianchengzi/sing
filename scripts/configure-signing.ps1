# Run from the private signing backup folder. Never add sing-fixed.jks to git.
$ErrorActionPreference = 'Stop'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'Install GitHub CLI first: winget install --id GitHub.cli ; then reopen PowerShell.'
}
gh auth status
if ($LASTEXITCODE -ne 0) {
    gh auth login --hostname github.com --web
    if ($LASTEXITCODE -ne 0) { throw 'GitHub sign-in failed.' }
}
$keyPath = Join-Path $PSScriptRoot 'sing-fixed.jks'
if (-not (Test-Path $keyPath)) { throw 'sing-fixed.jks must be beside this script. Use the private backup ZIP.' }
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath)) | gh secret set SING_KEYSTORE_BASE64 --repo shandianchengzi/sing
if ($LASTEXITCODE -ne 0) { throw 'Saving the private signing secret failed.' }
'android' | gh secret set SING_KEYSTORE_PASSWORD --repo shandianchengzi/sing
if ($LASTEXITCODE -ne 0) { throw 'Saving the key password failed.' }
gh workflow run apk.yml --repo shandianchengzi/sing
if ($LASTEXITCODE -ne 0) { throw 'Secrets saved; start Build APK manually in GitHub Actions.' }
Write-Host 'Fixed signing configured. Build APK has been started. Keep the private backup safe.'
