param(
    [string]$SourceKeyPath,
    [string]$VmHost = '192.168.146.128',
    [int]$LocalPort = 27018,
    [switch]$SkipLaunch
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyDestination = Join-Path $env:USERPROFILE '.ssh\logistics-mongodb-tunnel_ed25519'
$knownHosts = Join-Path $env:USERPROFILE '.ssh\known_hosts'
$sshDirectory = Split-Path -Parent $keyDestination

function Resolve-OpenSshExecutable([string]$Name) {
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command -and $command.Source) { return $command.Source }
    $candidate = Join-Path $env:WINDIR "System32\OpenSSH\$Name.exe"
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    $candidate = Join-Path $env:ProgramFiles "Git\usr\bin\$Name.exe"
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    throw "OpenSSH $Name.exe was not found."
}

function Resolve-PowerShellExecutable {
    $candidate = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    $candidate = Join-Path $PSHOME 'powershell.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    $candidate = Join-Path $PSHOME 'pwsh.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    throw 'Could not locate a PowerShell executable for the sign-in shortcut.'
}

if ([string]::IsNullOrWhiteSpace($SourceKeyPath)) {
    $SourceKeyPath = Get-ChildItem -Path "$env:TEMP\logistics-ssh-*\id_ed25519" -File -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($SourceKeyPath) -or -not (Test-Path -LiteralPath $SourceKeyPath -PathType Leaf)) {
    throw 'The existing local VM SSH key could not be found. No key was generated or overwritten.'
}
if (-not (Test-Path -LiteralPath $knownHosts -PathType Leaf)) {
    throw "Trusted VM host keys are missing: $knownHosts"
}
$sshPath = Resolve-OpenSshExecutable 'ssh'
$sshKeygenPath = Resolve-OpenSshExecutable 'ssh-keygen'
$trustedHost = & $sshKeygenPath -F $VmHost -f $knownHosts -q 2>$null
if ($LASTEXITCODE -ne 0) { throw "The VM fingerprint for $VmHost is not present in the current user's known_hosts file." }

if (-not (Test-Path -LiteralPath $sshDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $sshDirectory -Force | Out-Null
}
if (-not (Test-Path -LiteralPath $keyDestination -PathType Leaf)) {
    Copy-Item -LiteralPath $SourceKeyPath -Destination $keyDestination
}
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$keyAcl = Get-Acl -LiteralPath $keyDestination
$systemSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$expectedSids = @($identity.User.Value, $systemSid.Value)
$actualRules = @($keyAcl.Access | ForEach-Object {
    [pscustomobject]@{
        Sid = $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value
        Rights = $_.FileSystemRights
        Type = $_.AccessControlType
        Inherited = $_.IsInherited
    }
})
$ownerSid = ([System.Security.Principal.NTAccount]::new($keyAcl.Owner)).Translate([System.Security.Principal.SecurityIdentifier]).Value
$aclIsAlreadySafe = $keyAcl.AreAccessRulesProtected -and $ownerSid -eq $identity.User.Value -and
    $actualRules.Count -eq 2 -and @($actualRules | Where-Object {
        $_.Sid -notin $expectedSids -or $_.Rights -ne [System.Security.AccessControl.FileSystemRights]::FullControl -or
        $_.Type -ne [System.Security.AccessControl.AccessControlType]::Allow -or $_.Inherited
    }).Count -eq 0
if (-not $aclIsAlreadySafe) {
    $keyAcl.SetAccessRuleProtection($true, $false)
    if ($ownerSid -ne $identity.User.Value) { $keyAcl.SetOwner($identity.User) }
    foreach ($accessRule in @($keyAcl.Access)) { [void]$keyAcl.RemoveAccessRuleAll($accessRule) }
    foreach ($sid in @($identity.User, $systemSid)) {
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $sid, [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.AccessControlType]::Allow)
        $keyAcl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $keyDestination -AclObject $keyAcl
}

$sshArguments = @('-i', $keyDestination, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
    '-o', 'ConnectTimeout=8', '-o', 'StrictHostKeyChecking=yes', "-o", "UserKnownHostsFile=$knownHosts",
    '-o', 'LogLevel=ERROR', "root@$VmHost", 'exit 0')
& $sshPath @sshArguments
if ($LASTEXITCODE -ne 0) { throw 'The existing SSH key failed a non-interactive authentication check. Autostart was not installed or launched.' }

$startupDirectory = [Environment]::GetFolderPath('Startup')
$shortcutPath = Join-Path $startupDirectory 'Logistics MongoDB SSH Tunnel.lnk'
$launcherPath = Join-Path $PSScriptRoot 'start-mongo-tunnel.ps1'
$powershellPath = Resolve-PowerShellExecutable
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $powershellPath
$shortcut.Arguments = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$launcherPath`" -KeyPath `"$keyDestination`" -VmHost `"$VmHost`" -LocalPort $LocalPort"
$shortcut.WorkingDirectory = $projectRoot
$shortcut.Description = 'Maintain and automatically reconnect the local logistics MongoDB SSH tunnel.'
$shortcut.WindowStyle = 7
$shortcut.Save()

Write-Host "Saved the existing SSH key in $keyDestination with access limited to the current user and Windows SYSTEM."
Write-Host "Installed automatic MongoDB tunnel startup at user sign-in: $shortcutPath"
if (-not $SkipLaunch) {
    & $launcherPath -KeyPath $keyDestination -VmHost $VmHost -LocalPort $LocalPort
}
