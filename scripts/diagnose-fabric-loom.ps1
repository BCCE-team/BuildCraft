# Diagnostics for Fabric Loom plugin resolution in the 26.X Stonecutter build.
# Compatible with Windows PowerShell 5.1 and PowerShell 7.
# Usage (from repository root):
#   .\scripts\diagnose-fabric-loom.ps1
#   .\scripts\diagnose-fabric-loom.ps1 -Gradle
param([switch]$Gradle)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = '1.18.4'
$modulePom = "net/fabricmc/fabric-loom/$version/fabric-loom-$version.pom"
$markerPom = "net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/$version/net.fabricmc.fabric-loom.gradle.plugin-$version.pom"

# Windows PowerShell 5.1 sometimes defaults to TLS 1.0; do not disable TLS
# verification. Gradle's JVM has an independent certificate store.
try {
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$probes = @(
    @{ Label = 'Fabric official module'; Url = "https://maven.fabricmc.net/$modulePom" },
    @{ Label = 'Fabric official plugin marker'; Url = "https://maven.fabricmc.net/$markerPom" },
    @{ Label = 'Fabric CDN module'; Url = "https://fabricmc.forgecdn.net/$modulePom" },
    @{ Label = 'Fabric alternate mirror module'; Url = "https://maven.modmuss50.me/$modulePom" }
)

$passes = 0
Write-Host "Fabric Loom $version dependency availability" -ForegroundColor Cyan
foreach ($probe in $probes) {
    Write-Host "`n[$($probe.Label)] $($probe.Url)"
    try {
        $result = Invoke-WebRequest -Uri $probe.Url -Method Get -UseBasicParsing -TimeoutSec 20
        if ($result.StatusCode -eq 200 -and $result.Content -match '<project(?:\s|>)') {
            Write-Host '  OK: HTTP 200, Maven POM XML received' -ForegroundColor Green
            $passes++
        } else {
            Write-Warning "Unexpected response: HTTP $($result.StatusCode), body is not a Maven POM"
        }
    } catch {
        $status = 'no HTTP response'
        $errorResponse = $_.Exception.Response
        if ($null -ne $errorResponse) {
            try { $status = "HTTP $([int]$errorResponse.StatusCode)" } catch { }
        }
        Write-Warning "$status : $($_.Exception.Message)"
    }
}

if ($passes -eq 0) {
    Write-Warning 'No Loom POM could be fetched through PowerShell. Check DNS, proxy, firewall, TLS and Maven access.'
} else {
    Write-Host "`n$passes/$($probes.Count) endpoints returned a Maven POM." -ForegroundColor Green
    Write-Host 'NOTE: This tests Windows HTTPS, not the JVM trust store or Gradle cache.'
}

if ($Gradle) {
    $wrapper = Join-Path $root 'builds\26.X\gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) {
        throw "Missing Gradle wrapper at $wrapper"
    }
    $logDir = Join-Path $root 'logs\loom-resolution'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $logFile = Join-Path $logDir ('loom-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    Push-Location (Join-Path $root 'builds\26.X')
    $previousErrorAction = $ErrorActionPreference
    try {
        # Native stderr redirection can generate nonterminating ErrorRecords
        # on Windows PowerShell 5.1. Do not treat Gradle log lines as fatal
        # PowerShell exceptions; rely on Gradle's actual process exit code.
        $ErrorActionPreference = 'Continue'
        Write-Host "`nChecking Gradle plugin resolution (--info); log: $logFile" -ForegroundColor Cyan
        & $wrapper '--no-daemon' '--console=plain' '--info' '--refresh-dependencies' ':26.3-fabric:help' 2>&1 |
            Tee-Object -FilePath $logFile
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
        Pop-Location
    }
    Write-Host "Gradle exit code: $exitCode"
    Write-Host "Detailed Gradle log: $logFile"
    if ($exitCode -ne 0) { exit $exitCode }
}

if ($passes -eq 0) { exit 1 }
