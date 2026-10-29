param(
    [string]$KeyPath,
    [string]$VmHost = '192.168.146.128',
    [int]$LocalPort = 27018
)

$ErrorActionPreference = 'Stop'
$watcher = Join-Path $PSScriptRoot 'keep-mongo-tunnel.ps1'
$key = if (-not [string]::IsNullOrWhiteSpace($KeyPath)) { $KeyPath } else { Join-Path $env:USERPROFILE '.ssh\logistics-mongodb-tunnel_ed25519' }
if (-not (Test-Path -LiteralPath $key -PathType Leaf)) {
    throw "MongoDB tunnel SSH key is missing: $key. Run scripts/install-mongo-tunnel-autostart.ps1 once to install the existing local key safely."
}
$knownHosts = Join-Path $env:USERPROFILE '.ssh\known_hosts'
if (-not (Test-Path -LiteralPath $knownHosts -PathType Leaf)) {
    throw "Trusted SSH host keys are missing: $knownHosts. Connect to the VM interactively and verify its fingerprint first."
}

$powershell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    $powershell = Join-Path $PSHOME 'powershell.exe'
}
if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    $powershell = Join-Path $PSHOME 'pwsh.exe'
}
if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) {
    throw 'Could not locate a PowerShell executable to run the MongoDB tunnel supervisor.'
}
$argumentLine = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -KeyPath "{1}" -VmHost "{2}" -LocalPort {3}' -f $watcher, $key, $VmHost, $LocalPort
Start-Process -FilePath $powershell -ArgumentList $argumentLine -WorkingDirectory (Split-Path -Parent $PSScriptRoot) -WindowStyle Hidden | Out-Null
Write-Host "MongoDB tunnel supervisor started; it will maintain localhost:$LocalPort and reconnect after SSH drops."
