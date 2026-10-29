param(
    [string]$ApiBase='http://localhost:8080',
    [string]$VmHost='192.168.146.128',
    [string]$IdentityFile,
    [PSCredential]$Credential
)
$ErrorActionPreference='Stop'
if($VmHost -notmatch '^[A-Za-z0-9.-]+$'){throw 'Invalid VM host.'}
. (Join-Path $PSScriptRoot 'api-client.ps1')
if(-not $Credential){$Credential=Get-Credential -Message 'Logistics administrator account (not VM/MySQL account)'}
$run=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$root=Split-Path -Parent $PSScriptRoot
$localDir=Join-Path $root "backend\target\od-jobs\$run"
New-Item -ItemType Directory -Path $localDir -Force | Out-Null
$utf8=[System.Text.UTF8Encoding]::new($false)
$session=Invoke-LogisticsApi -Uri "$ApiBase/api/auth/login" -Method Post -Body @{username=$Credential.UserName;password=$Credential.GetNetworkCredential().Password}
try {
    $headers=@{Authorization="Bearer $($session.token)"}
    Invoke-WebRequest -UseBasicParsing -Uri "$ApiBase/api/statistics/offline/input" -Headers $headers -OutFile (Join-Path $localDir 'raw-tasks.jsonl')
    foreach($line in [System.IO.File]::ReadLines((Join-Path $localDir 'raw-tasks.jsonl'))){
        if($line.Trim()){ $record=$line | ConvertFrom-Json; if($record.schemaVersion -ne 1){throw 'Invalid raw task export schema.'} }
    }
} finally {Invoke-LogisticsApi -Uri "$ApiBase/api/auth/logout" -Method Post -Headers @{Authorization="Bearer $($session.token)"} | Out-Null}
foreach($entry in @(@('hadoop\od_mapper.py','od_mapper.py'),@('hadoop\od_reducer.py','od_reducer.py'),@('vm\run-od-mapreduce.sh','run-od-mapreduce.sh'))){
    $content=[System.IO.File]::ReadAllText((Join-Path $PSScriptRoot $entry[0])).Replace("`r`n","`n")
    [System.IO.File]::WriteAllText((Join-Path $localDir $entry[1]),$content,$utf8)
}
$sshArgs=@('-o','ConnectTimeout=8')
if($IdentityFile){$sshArgs+=@('-i',$IdentityFile)}
$remoteDir="/tmp/logistics-od-$run"
& ssh @sshArgs "root@$VmHost" "mkdir -p '$remoteDir'"
if($LASTEXITCODE -ne 0){throw 'SSH setup failed.'}
foreach($file in Get-ChildItem -LiteralPath $localDir -File){
    & scp @sshArgs $file.FullName "root@${VmHost}:$remoteDir/"
    if($LASTEXITCODE -ne 0){throw 'Upload failed.'}
}
& ssh @sshArgs "root@$VmHost" "bash '$remoteDir/run-od-mapreduce.sh' '$remoteDir/raw-tasks.jsonl'"
if($LASTEXITCODE -ne 0){throw 'Hadoop job failed. Previous completed statistics remain available.'}
Write-Host 'Hadoop completed. Refresh the administrator statistics page. Local inputs retained in:' $localDir
