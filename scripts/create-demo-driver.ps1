param(
    [string]$ApiBase = 'http://localhost:8080/api'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'api-client.ps1')

function Read-PlainSecret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    return [System.Net.NetworkCredential]::new('', $secure).Password
}

$adminUsername = Read-Host 'Admin username'
$adminPassword = Read-PlainSecret 'Admin password (hidden)'
$driverUsername = Read-Host 'New driver username (3-32 ASCII characters)'
$driverPassword = Read-PlainSecret 'New driver password (at least 10 characters, hidden)'
$driverName = Read-Host 'Driver display name'
$driverPhone = Read-Host 'Driver phone (optional)'

$registerBody = @{
    username = $driverUsername
    password = $driverPassword
    displayName = $driverName
    phone = $driverPhone
}
Invoke-LogisticsApi -Uri "$ApiBase/auth/register" -Method Post -Body $registerBody | Out-Null

$loginBody = @{ username = $adminUsername; password = $adminPassword }
$login = Invoke-LogisticsApi -Uri "$ApiBase/auth/login" -Method Post -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.token)" }
$pending = Invoke-LogisticsApi -Uri "$ApiBase/admin/drivers/pending" -Headers $headers
$driver = @($pending) | Where-Object { $_.username -eq $driverUsername } | Select-Object -First 1
if ($null -eq $driver) { throw 'Driver was registered but is not in the pending list.' }

$reviewBody = @{ approve = $true; note = 'Local fixed demo driver' }
Invoke-LogisticsApi -Uri "$ApiBase/admin/drivers/$($driver.id)/review" -Method Post -Headers $headers -Body $reviewBody | Out-Null
Write-Host 'Demo driver registered and approved. Use that account to log in to the driver portal.'
