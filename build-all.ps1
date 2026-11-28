param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @("buildAndCollect")
}
elseif ($GradleArgs[0] -eq "assembly") {
    Write-Warning "Gradle task 'assembly' does not exist; using 'assemble'."
    $GradleArgs[0] = "assemble"
}

# The build generations require different Gradle JVMs, not just compiler toolchains.
# Both modern generations use Gradle on 25; 1.21.X compiles/runs on 21.
# Reuse the JDK discovery from run-ci-local.py (including .jdks and Gradle JDKs).
$Python = Get-Command python.exe -ErrorAction SilentlyContinue
$PythonPrefix = @()
if (-not $Python) {
    $Python = Get-Command py.exe -ErrorAction SilentlyContinue
    $PythonPrefix = @("-3.11")
}
if (-not $Python) {
    throw "Python 3.11+ is required for automatic JDK discovery. Install Python or add python.exe/py.exe to PATH."
}

$Selector = Join-Path $RepositoryRoot "scripts/select-build-java.py"
$JavaSelectionJson = & $Python.Source @PythonPrefix $Selector 17 21 25
if ($LASTEXITCODE -ne 0) {
    throw "Automatic Java 17/21/25 discovery failed; see the diagnostics above."
}
$JavaSelection = $JavaSelectionJson | ConvertFrom-Json

$OriginalJavaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
$OriginalPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
$OriginalJava21Home = [Environment]::GetEnvironmentVariable("JAVA_HOME_21_X64", "Process")
$Result = 0

try {
    $env:JAVA_HOME_21_X64 = [string]$JavaSelection.PSObject.Properties["21"].Value
    foreach ($Stage in @(
        @{ Generation = "old"; Java = 17 },
        @{ Generation = "1.21.X"; Java = 25 },
        @{ Generation = "26.X"; Java = 25 }
    )) {
        $Major = [string]$Stage.Java
        $Property = $JavaSelection.PSObject.Properties[$Major]
        if ($null -eq $Property -or -not $Property.Value) {
            throw "No JDK $Major home was selected for $($Stage.Generation)."
        }
        $HomeDir = [string]$Property.Value
        $JavaExe = Join-Path $HomeDir "bin/java.exe"
        if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
            throw "JDK $Major is missing java.exe: $JavaExe"
        }

        # Keep the original PATH for every generation; do not stack old JDKs.
        $env:JAVA_HOME = $HomeDir
        $env:PATH = (Join-Path $HomeDir "bin") + [IO.Path]::PathSeparator + $OriginalPath

        $BuildRoot = Join-Path $RepositoryRoot "builds/$($Stage.Generation)"
        Write-Host "==> Running $($Stage.Generation) BuildCraft build with Java $Major ($HomeDir): $($GradleArgs -join ' ')"
        Push-Location $BuildRoot
        try {
            & (Join-Path $BuildRoot "gradlew.bat") @GradleArgs
            $Result = $LASTEXITCODE
        }
        finally {
            Pop-Location
        }
        if ($Result -ne 0) {
            break
        }
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
