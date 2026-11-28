param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("1.19.2", "1.20.1", "1.21.1", "1.21.11", "26.1.2", "26.2", "26.3")]
    [string] $Version,

    [ValidateSet("auto", "neoforge", "fabric")]
    [string] $Loader = "auto",

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

$Targets = @{
    "1.19.2"  = @{ Generation = "old"; Target = "1.19.2-forge" }
    "1.20.1"  = @{ Generation = "old"; Target = "1.20.1-forge" }
    "1.21.1"  = @{ Generation = "1.21.X"; Target = "1.21.1-neoforge" }
    "1.21.11" = @{ Generation = "1.21.X"; Target = "1.21.11-neoforge" }
    "26.1.2"  = @{ Generation = "26.X"; Target = "26.1.2-neoforge" }
    "26.2"    = @{ Generation = "26.X"; Target = "26.2-neoforge" }
    "26.3"    = @{ Generation = "26.X"; Target = "26.3-neoforge" }
}

$Config = $Targets[$Version]
if ($Loader -eq "fabric") {
    if ($Version -notin @("1.21.11", "26.1.2", "26.2", "26.3")) { throw "Fabric is supported on Minecraft 1.21.11, 26.1.2, 26.2 and 26.3" }
    $Config = @{ Generation = $(if ($Version -eq "1.21.11") { "1.21.X" } else { "26.X" }); Target = "$Version-fabric" }
}
$BuildRoot = Join-Path $RepositoryRoot "builds/$($Config.Generation)"
$Task = ":$($Config.Target):runClient"
$JavaMajor = switch ($Config.Generation) {
    "old" { 17 }
    "1.21.X" { 25 }
    "26.X" { 25 }
    default { throw "Unsupported BuildCraft build generation: $($Config.Generation)" }
}

# The Gradle daemon and the game/compiler toolchain are separate.
# Loom 1.18.4 needs Java 25 even for Minecraft 1.21.11. All projects
# in 1.21.X share that daemon, but the game and javac stay on Java 21.
$RequiredJava = @($JavaMajor)
if ($Config.Generation -eq "1.21.X") { $RequiredJava += 21 }
$Python = Get-Command python.exe -ErrorAction SilentlyContinue
$PythonPrefix = @()
if (-not $Python) {
    $Python = Get-Command py.exe -ErrorAction SilentlyContinue
    $PythonPrefix = @("-3.11")
}
if (-not $Python) {
    throw "Python 3.11+ is required for JDK discovery. Install Python or add it to PATH."
}

$Selector = Join-Path $RepositoryRoot "scripts/select-build-java.py"
$JavaSelectionJson = & $Python.Source @PythonPrefix $Selector @RequiredJava
if ($LASTEXITCODE -ne 0) {
    throw "Cannot locate required JDK(s) $($RequiredJava -join ", ") for $($Config.Target); see the diagnostics above."
}
$JavaSelection = $JavaSelectionJson | ConvertFrom-Json
$SelectedJava = $JavaSelection.PSObject.Properties[[string]$JavaMajor]
if ($null -eq $SelectedJava -or -not $SelectedJava.Value) {
    throw "JDK discovery did not return Java $JavaMajor."
}
$JavaHome = [string]$SelectedJava.Value
$JavaExe = Join-Path $JavaHome "bin/java.exe"
if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
    throw "JDK $JavaMajor has no java.exe: $JavaExe"
}

$OriginalJavaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
$OriginalPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
$OriginalJava21Home = [Environment]::GetEnvironmentVariable("JAVA_HOME_21_X64", "Process")
$Result = 0

Write-Host "==> Running BuildCraft client $Version ($($Config.Target))"
Write-Host "==> Gradle task: $Task"
Write-Host "==> Gradle JVM: Java $JavaMajor ($JavaHome)"

try {
    if ($Config.Generation -eq "1.21.X") {
        $env:JAVA_HOME_21_X64 = [string]$JavaSelection.PSObject.Properties["21"].Value
        Write-Host "==> Minecraft/compiler toolchain: Java 21 ($env:JAVA_HOME_21_X64)"
    }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome "bin") + [IO.Path]::PathSeparator + $OriginalPath
    Push-Location $BuildRoot
    try {
        & .\gradlew.bat $Task @GradleArgs
        $Result = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($null -eq $OriginalJavaHome) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    }
    else {
        $env:JAVA_HOME = $OriginalJavaHome
    }
    $env:PATH = $OriginalPath
    if ($null -eq $OriginalJava21Home) {
        Remove-Item Env:JAVA_HOME_21_X64 -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_HOME_21_X64 = $OriginalJava21Home
    }
}
exit $Result
