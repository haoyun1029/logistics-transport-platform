[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [string]$RouteEvidence,
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evidence = (Resolve-Path $EvidenceDir).Path
$checks = [System.Collections.Generic.List[object]]::new()

function Run-Check([string]$Name, [string[]]$Arguments) {
    & $Python @Arguments
    $code = $LASTEXITCODE
    $checks.Add([pscustomobject]@{ Check = $Name; Passed = ($code -eq 0); ExitCode = $code })
    if ($code -ne 0) { throw "$Name failed with exit code $code" }
}

Push-Location $root
try {
    Run-Check 'acceptance tests' @('-m', 'unittest', 'discover', '-s', 'scripts/acceptance', '-p', 'test_*.py', '-v')
    Run-Check 'evidence manifest' @('scripts/acceptance/verify_evidence_manifest.py', $evidence)
    if ($RouteEvidence) {
        Run-Check 'route evidence structure' @('scripts/acceptance/validate_route_evidence.py', (Resolve-Path $RouteEvidence).Path)
    } else {
        $checks.Add([pscustomobject]@{ Check = 'route evidence structure'; Passed = $false; ExitCode = $null; Note = 'skipped: provide -RouteEvidence for a route bundle' })
    }
} finally {
    Pop-Location
}

$checks | Format-Table -AutoSize
if ($checks.Where({ $_.Check -ne 'route evidence structure' -and -not $_.Passed }).Count -gt 0) { exit 1 }
if ($checks.Where({ $_.Check -eq 'route evidence structure' -and $_.Passed }).Count -eq 0) {
    Write-Warning 'Technical checks passed, but no route evidence bundle was supplied; final road approval remains pending.'
}
