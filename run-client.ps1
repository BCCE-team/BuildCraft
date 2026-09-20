param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("1.19.2", "1.20.1", "1.21.1", "1.21.11")]
    [string] $Version,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

$Targets = @{
    "1.19.2"  = @{ Generation = "legacy"; Target = "1.19.2-forge" }
    "1.20.1"  = @{ Generation = "legacy"; Target = "1.20.1-forge" }
    "1.21.1"  = @{ Generation = "modern"; Target = "1.21.1-neoforge" }
    "1.21.11" = @{ Generation = "modern"; Target = "1.21.11-neoforge" }
}

$Config = $Targets[$Version]
$BuildRoot = Join-Path $RepositoryRoot "builds/$($Config.Generation)"
$Task = ":$($Config.Target):runClient"

Write-Host "==> Running BuildCraft client $Version ($($Config.Target))"
Write-Host "==> Gradle task: $Task"

Push-Location $BuildRoot
try {
    & .\gradlew.bat $Task @GradleArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
