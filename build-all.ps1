param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @("buildAndCollect")
}

foreach ($Generation in @("old", "1.21.X")) {
    $BuildRoot = Join-Path $RepositoryRoot "builds/$Generation"
    Write-Host "==> Running $Generation BuildCraft build: $($GradleArgs -join ' ')"
    Push-Location $BuildRoot
    try {
        & .\gradlew.bat @GradleArgs
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
    finally {
        Pop-Location
    }
}
