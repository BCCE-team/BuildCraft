# Pass CLI selectors through verbatim, including --validate-api-boundary.
# Keep legacy PowerShell spellings for the pre-existing switches.
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path $PSScriptRoot).Path

$runnerArgs = @()
foreach ($argument in $args) {
    $token = [string]$argument
    switch -Regex ($token) {
        '^(?i:-ValidateOnly)$' { $runnerArgs += "--validate-only"; continue }
        '^(?i:-DryRun)$' { $runnerArgs += "--dry-run"; continue }
        '^(?i:-RunId)$' { $runnerArgs += "--run-id"; continue }
        '^(?i:-From)$' { $runnerArgs += "--from"; continue }
        '^(?i:-ListSteps)$' { $runnerArgs += "--list-steps"; continue }
        default { $runnerArgs += $token }
    }
}

# Local validation, AutoTests and GameTests run natively on Windows via gradlew.bat.
# Client and dedicated-server smoke tests are GitHub CI-only.
$python = Get-Command python.exe -ErrorAction SilentlyContinue
if ($python) {
    & $python.Source "$repo/scripts/run-ci-local.py" @runnerArgs
    exit $LASTEXITCODE
}

$py = Get-Command py.exe -ErrorAction SilentlyContinue
if ($py) {
    & $py.Source -3.11 "$repo/scripts/run-ci-local.py" @runnerArgs
    exit $LASTEXITCODE
}

Write-Error "Python 3.11+ is required to run the local CI. Install Python or add python.exe/py.exe to PATH."
exit 2
