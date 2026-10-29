param(
    [string]$VmAddress = $(if ($env:DB_HOST) { $env:DB_HOST } else { '192.168.146.128' })
)

$services = @(
    @{ Name = 'SSH'; Port = 22 },
    @{ Name = 'ZooKeeper'; Port = 2181 },
    @{ Name = 'Kafka'; Port = 9092 },
    @{ Name = 'MySQL'; Port = 3306 },
    @{ Name = 'MongoDB'; Port = 27017 },
    @{ Name = 'Flink REST'; Port = 8081 },
    @{ Name = 'HDFS RPC'; Port = 8020 },
    @{ Name = 'HDFS Web'; Port = 9870 },
    @{ Name = 'YARN ResourceManager'; Port = 8088 }
)

$results = foreach ($service in $services) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $operation = $client.BeginConnect($VmAddress, $service.Port, $null, $null)
        $reachable = $operation.AsyncWaitHandle.WaitOne(1000) -and $client.Connected
        if ($reachable) { $client.EndConnect($operation) }
        [pscustomobject]@{
            Service = $service.Name
            Address = '{0}:{1}' -f $VmAddress, $service.Port
            TcpReachable = $reachable
        }
    }
    finally {
        $client.Dispose()
    }
}

# MongoDB is intentionally bound to VM loopback. When the optional SSH tunnel is
# running, the Windows backend reaches it through localhost:27018 instead.
$tunnelClient = [System.Net.Sockets.TcpClient]::new()
try {
    $operation = $tunnelClient.BeginConnect('127.0.0.1', 27018, $null, $null)
    $tunnelReachable = $operation.AsyncWaitHandle.WaitOne(1000) -and $tunnelClient.Connected
    if ($tunnelReachable) { $tunnelClient.EndConnect($operation) }
    $results += [pscustomobject]@{
        Service = 'MongoDB (SSH tunnel)'
        Address = '127.0.0.1:27018'
        TcpReachable = $tunnelReachable
    }
}
finally {
    $tunnelClient.Dispose()
}

$results | Format-Table -AutoSize
