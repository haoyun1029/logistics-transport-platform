param(
    [ValidateSet('PrepareEnv', 'Bootstrap')]
    [string]$Mode = 'PrepareEnv',
    [string]$ApiBase = 'http://localhost:8080/api'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'api-client.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$examplePath = Join-Path $projectRoot '.env.example'

function Set-EnvValue([string[]]$content, [string]$key, [string]$value) {
    $escapedKey = [regex]::Escape($key)
    $found = $false
    $result = foreach ($line in $content) {
        if ($line -match "^$escapedKey=") {
            $found = $true
            "$key=$value"
        } else {
            $line
        }
    }
    if (-not $found) { $result += "$key=$value" }
    return $result
}

if ($Mode -eq 'PrepareEnv') {
    if (-not (Test-Path $envPath)) {
        Copy-Item $examplePath $envPath
        Write-Host "Created $envPath from .env.example"
    }

    $dbPassword = Read-Host 'MySQL logistics_app password (hidden)' -AsSecureString
    $dbPasswordPlain = [System.Net.NetworkCredential]::new('', $dbPassword).Password
    if ([string]::IsNullOrWhiteSpace($dbPasswordPlain) -or $dbPasswordPlain -eq 'replace_me') {
        throw 'MySQL password must be set and cannot be replace_me.'
    }
    $bootstrapToken = Read-Host 'Set one-time bootstrap token (example: local-bootstrap-2026)'
    if ([string]::IsNullOrWhiteSpace($bootstrapToken) -or $bootstrapToken -eq 'replace_with_a_long_random_setup_token') {
        throw 'Bootstrap token must be set and cannot be the example placeholder.'
    }

    $lines = Get-Content $envPath -Encoding UTF8
    $lines = Set-EnvValue $lines 'DB_PASSWORD' $dbPasswordPlain
    $lines = Set-EnvValue $lines 'APP_BOOTSTRAP_TOKEN' $bootstrapToken
    [System.IO.File]::WriteAllLines($envPath, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Local .env is ready. Start the backend:'
    Write-Host '  cd backend'
    Write-Host '  mvn spring-boot:run'
    Write-Host 'After the backend is ready, run: ./scripts/initialize-local.ps1 -Mode Bootstrap'
    exit 0
}

$setupToken = Read-Host 'APP_BOOTSTRAP_TOKEN from .env'
$username = Read-Host 'Admin username (3-32 ASCII letters, digits, dot, underscore or dash)'
$password = Read-Host 'Admin password (at least 10 characters, hidden)' -AsSecureString
$passwordPlain = [System.Net.NetworkCredential]::new('', $password).Password
$displayName = Read-Host 'Admin display name'

$body = @{
    setupToken = $setupToken
    username = $username
    password = $passwordPlain
    displayName = $displayName
}

try {
    Invoke-LogisticsApi -Uri "$ApiBase/auth/bootstrap" -Method Post -Body $body | Out-Null
    Write-Host 'Super admin initialized. Use the username and password you just entered to log in.'
} catch {
    Write-Error "Bootstrap failed: $($_.Exception.Message)"
    exit 1
}
