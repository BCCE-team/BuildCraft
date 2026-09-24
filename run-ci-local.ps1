param(
    [switch]$ValidateOnly,
    [switch]$DryRun,
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path $PSScriptRoot).Path

$runnerArgs = @()
if ($ValidateOnly) { $runnerArgs += "--validate-only" }
if ($DryRun) { $runnerArgs += "--dry-run" }
if ($RunId) { $runnerArgs += @("--run-id", $RunId) }

# Full local CI runs natively on Windows. The Python runner uses gradlew.bat and
# native server launch monitoring; client smoke is intentionally GitHub-only.
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
