function Invoke-LogisticsApi {
    param(
        [string]$Uri,
        [string]$Method = 'Get',
        [hashtable]$Headers = @{},
        [hashtable]$Body
    )

    $request = @{ Uri = $Uri; Method = $Method; Headers = $Headers; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))
    }
    try {
        Invoke-RestMethod @request
    } catch {
        $response = $_.Exception.Response
        $errorText = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($errorText) -and $null -ne $response -and
            $null -ne $response.PSObject.Methods['GetResponseStream']) {
            $reader = [System.IO.StreamReader]::new($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
            try { $errorText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
        $apiError = $null
        if (-not [string]::IsNullOrWhiteSpace($errorText)) {
            try { $apiError = $errorText | ConvertFrom-Json -ErrorAction Stop } catch { }
        }
        if ($null -ne $apiError -and $apiError.code -and $apiError.message) {
            throw "API request failed [$($apiError.code)]: $($apiError.message)"
        }
        if ($null -ne $response) {
            throw "API request failed (HTTP $([int]$response.StatusCode)). Check the backend log for details."
        }
        throw 'Cannot reach the backend. Check that it is running and ApiBase is correct.'
    }
}
