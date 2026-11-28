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
    if ($Version -notin @("26.2", "26.3")) { throw "Fabric is supported on Minecraft 26.2 and 26.3" }
    $Config = @{ Generation = "26.X"; Target = "$Version-fabric" }
}
$BuildRoot = Join-Path $RepositoryRoot "builds/$($Config.Generation)"
$Task = ":$($Config.Target):runClient"
$JavaMajor = switch ($Config.Generation) {
    "old" { 17 }
    "1.21.X" { 21 }
    "26.X" { 25 }
    default { throw "Unsupported BuildCraft build generation: $($Config.Generation)" }
}

# runClient needs the build-generation JVM, not just a Java compiler toolchain.
# The IDEA Gradle configurations use per-build daemon criteria as well; this
# launcher keeps standalone PowerShell runs correct when the default Java is 19/25.
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
$JavaSelectionJson = & $Python.Source @PythonPrefix $Selector $JavaMajor
if ($LASTEXITCODE -ne 0) {
    throw "Cannot locate JDK $JavaMajor for $($Config.Target); see the diagnostics above."
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
$Result = 0

Write-Host "==> Running BuildCraft client $Version ($($Config.Target))"
Write-Host "==> Gradle task: $Task"
Write-Host "==> Gradle JVM: Java $JavaMajor ($JavaHome)"

try {
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
}
exit $Result
