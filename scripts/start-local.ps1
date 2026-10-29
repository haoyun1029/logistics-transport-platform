param(
    [switch]$Frontend
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
$frontendRoot = Join-Path $projectRoot 'frontend'

if ($Frontend) {
    Push-Location $frontendRoot
    try {
        npm run dev
    }
    finally {
        Pop-Location
    }
    exit $LASTEXITCODE
}

Push-Location $backendRoot
try {
    mvn spring-boot:run
}
finally {
    Pop-Location
}
