param(
    [string]$TaskId = 'DEMO-2026-002',
    [string]$BootstrapServers,
    [string]$Topic
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$localSettings = @{}
if (Test-Path $envPath) {
    foreach ($line in (Get-Content $envPath -Encoding UTF8)) {
        if ($line -match '^\s*(KAFKA_BOOTSTRAP_SERVERS|KAFKA_GPS_TOPIC)=(.*)$') {
            $localSettings[$matches[1]] = $matches[2].Trim()
        }
    }
}
if (-not $BootstrapServers) {
    $BootstrapServers = $env:KAFKA_BOOTSTRAP_SERVERS
    if (-not $BootstrapServers) { $BootstrapServers = $localSettings['KAFKA_BOOTSTRAP_SERVERS'] }
    if (-not $BootstrapServers) { $BootstrapServers = '192.168.146.128:9092' }
}
if (-not $Topic) {
    $Topic = $env:KAFKA_GPS_TOPIC
    if (-not $Topic) { $Topic = $localSettings['KAFKA_GPS_TOPIC'] }
    if (-not $Topic) { $Topic = 'logistics.gps.raw' }
}
$reportPath = Join-Path $projectRoot 'backend/target/surefire-reports/TEST-cn.logistics.platform.api.HealthControllerTest.xml'
if (-not (Test-Path $reportPath)) {
    throw 'Run mvn test in backend first. The probe reuses its resolved Java dependency classpath.'
}
[xml]$report = Get-Content $reportPath -Raw -Encoding UTF8
$probeClasspath = ($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
if (-not $probeClasspath) { throw 'No Java classpath found. Run mvn test in backend again.' }
$probeSource = Join-Path $projectRoot 'backend/tools/KafkaGpsProbe.java'
Write-Host 'Reading an existing Kafka snapshot; no consumer offsets will be committed.'
& java --class-path $probeClasspath $probeSource $BootstrapServers $Topic $TaskId
if ($LASTEXITCODE -ne 0) { throw 'Kafka GPS verification failed or the snapshot was incomplete. Check the output above.' }
