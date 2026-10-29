param(
    [Parameter(Mandatory = $true)][string]$KeyPath,
    [string]$VmHost = '192.168.146.128',
    [int]$LocalPort = 27018,
    [int]$HealthIntervalSeconds = 20,
    [int]$MaximumBackoffSeconds = 120
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logPath = Join-Path $projectRoot 'backend\target\mongo-tunnel-watch.log'
$sshOutputPath = Join-Path $projectRoot 'backend\target\mongo-tunnel-ssh.stdout.log'
$sshErrorPath = Join-Path $projectRoot 'backend\target\mongo-tunnel-ssh.stderr.log'
$knownHostsPath = Join-Path $env:USERPROFILE '.ssh\known_hosts'
$mutexName = "Local\LogisticsMongoTunnel-$LocalPort"
$mutex = [System.Threading.Mutex]::new($false, $mutexName)
$ownsMutex = $false
$ssh = $null
$consecutiveHealthFailures = 0
$restartCount = 0

function Write-WatcherLog([string]$Message) {
    try {
        $directory = Split-Path -Parent $logPath
        if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
        if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 262144) {
            $previous = "$logPath.1"
            if (Test-Path -LiteralPath $previous) { Remove-Item -LiteralPath $previous -Force }
            Move-Item -LiteralPath $logPath -Destination $previous -Force
        }
        Add-Content -LiteralPath $logPath -Encoding UTF8 -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $Message"
    } catch { }
}

function Get-MongoHealth {
    try {
        $result = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health/components' -TimeoutSec 5
        return @($result.components | Where-Object { $_.name -eq 'MongoDB' } | Select-Object -First 1)[0]
    } catch { return $null }
}

function Start-MongoSsh {
    $sshCommand = Get-Command ssh.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    $sshPath = if ($sshCommand -and $sshCommand.Source) { $sshCommand.Source } else { Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe' }
    if (-not (Test-Path -LiteralPath $sshPath -PathType Leaf)) { throw 'OpenSSH ssh.exe was not found.' }
    $arguments = @(
        '-N', '-T',
        '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=15',
        '-o', 'ServerAliveCountMax=3',
        '-o', 'TCPKeepAlive=yes',
        '-o', 'ConnectTimeout=10',
        '-o', 'BatchMode=yes',
        '-o', 'IdentitiesOnly=yes',
        '-o', 'PreferredAuthentications=publickey',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', "UserKnownHostsFile=$knownHostsPath",
        '-o', 'LogLevel=ERROR',
        '-i', $KeyPath,
        '-L', "127.0.0.1:${LocalPort}:127.0.0.1:27017",
        "root@$VmHost"
    )
    $argumentLine = ($arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    return Start-Process -FilePath $sshPath -ArgumentList $argumentLine -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -RedirectStandardOutput $sshOutputPath -RedirectStandardError $sshErrorPath -PassThru
}

try {
    try { $ownsMutex = $mutex.WaitOne(0, $false) }
    catch [System.Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { exit 0 }

    if (-not (Test-Path -LiteralPath $KeyPath -PathType Leaf)) { throw 'Persistent MongoDB tunnel key is missing.' }
    if (-not (Test-Path -LiteralPath $knownHostsPath -PathType Leaf)) { throw 'Trusted VM host key is missing from the current user SSH known_hosts file.' }
    Write-WatcherLog "MongoDB tunnel supervisor active for 127.0.0.1:$LocalPort."

    while ($true) {
        $occupied = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $occupied) {
            try {
                $ssh = Start-MongoSsh
                Start-Sleep -Milliseconds 900
                $ssh.Refresh()
                if ($ssh.HasExited) {
                    Write-WatcherLog "SSH connection attempt exited with code $($ssh.ExitCode)."
                    $ssh = $null
                    $restartCount = [Math]::Min($restartCount + 1, 6)
                    $delay = [Math]::Min(5 * [Math]::Pow(2, $restartCount - 1), $MaximumBackoffSeconds)
                    Start-Sleep -Seconds ([int]$delay)
                    continue
                }
                Write-WatcherLog "SSH tunnel process started (PID $($ssh.Id))."
            } catch {
                Write-WatcherLog "Could not start SSH tunnel: $($_.Exception.GetType().Name): $($_.Exception.Message)"
                $restartCount = [Math]::Min($restartCount + 1, 6)
                $delay = [Math]::Min(5 * [Math]::Pow(2, $restartCount - 1), $MaximumBackoffSeconds)
                Start-Sleep -Seconds ([int]$delay)
                continue
            }
        } elseif (-not $ssh) {
            $ssh = Get-CimInstance Win32_Process -Filter "Name = 'ssh.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -match "127\.0\.0\.1:${LocalPort}:127\.0\.0\.1:27017" } |
                Select-Object -First 1 |
                ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
            if (-not $ssh) {
                Write-WatcherLog "Local port $LocalPort is occupied by an unrelated process; leaving it untouched."
                Start-Sleep -Seconds $HealthIntervalSeconds
                continue
            }
            Write-WatcherLog "Adopted the existing MongoDB tunnel process (PID $($ssh.Id))."
        }

        Start-Sleep -Seconds $HealthIntervalSeconds
        $ssh.Refresh()
        if ($ssh.HasExited) {
            Write-WatcherLog "SSH tunnel disconnected (exit code $($ssh.ExitCode)); reconnecting."
            $ssh = $null
            $consecutiveHealthFailures = 0
            $restartCount = [Math]::Min($restartCount + 1, 6)
            $delay = [Math]::Min(5 * [Math]::Pow(2, $restartCount - 1), $MaximumBackoffSeconds)
            Start-Sleep -Seconds ([int]$delay)
            continue
        }

        $mongo = Get-MongoHealth
        if ($mongo -and $mongo.status -eq 'CONNECTED') {
            if ($consecutiveHealthFailures -gt 0) { Write-WatcherLog 'MongoDB ping recovered through the SSH tunnel.' }
            $consecutiveHealthFailures = 0
            $restartCount = 0
            continue
        }

        # If the backend itself is stopped or unreachable, retain the live SSH tunnel.
        if ($null -eq $mongo) {
            $consecutiveHealthFailures = 0
            continue
        }

        $consecutiveHealthFailures++
        Write-WatcherLog "Backend MongoDB ping failed ($consecutiveHealthFailures/3); checking SSH forwarding."
        if ($consecutiveHealthFailures -ge 3) {
            $sshPid = $ssh.Id
            try {
                $ssh.Refresh()
                if (-not $ssh.HasExited) { Stop-Process -Id $sshPid -Force -ErrorAction Stop }
                Write-WatcherLog "Recycling stalled SSH tunnel process (PID $sshPid)."
            } catch { Write-WatcherLog "SSH tunnel recycle failed; will retry."
            }
            $ssh = $null
            $consecutiveHealthFailures = 0
            $restartCount = [Math]::Min($restartCount + 1, 6)
            $delay = [Math]::Min(5 * [Math]::Pow(2, $restartCount - 1), $MaximumBackoffSeconds)
            Start-Sleep -Seconds ([int]$delay)
        }
    }
} catch {
    Write-WatcherLog "Supervisor stopped by a configuration error: $($_.Exception.GetType().Name)."
    throw
} finally {
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
