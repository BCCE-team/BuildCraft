@echo off
setlocal EnableExtensions
cd /d "%~dp0"

title BuildCraft standalone project materializer

echo.
echo BuildCraft Community Edition - materialize standalone project
echo.
echo   1. Minecraft 1.19.2  - Forge
echo   2. Minecraft 1.20.1  - Forge
echo   3. Minecraft 1.20.1  - Fabric ^(skeleton^)
echo   4. Minecraft 1.21.1  - NeoForge
echo   5. Minecraft 1.21.11 - NeoForge
echo.

set "TARGET="
set /p "CHOICE=Select target [1-5]: "
if "%CHOICE%"=="1" set "TARGET=1.19.2-forge"
if "%CHOICE%"=="2" set "TARGET=1.20.1-forge"
if "%CHOICE%"=="3" set "TARGET=1.20.1-fabric"
if "%CHOICE%"=="4" set "TARGET=1.21.1-neoforge"
if "%CHOICE%"=="5" set "TARGET=1.21.11-neoforge"

if not defined TARGET (
    echo.
    echo Invalid selection: %CHOICE%
    echo.
    pause
    exit /b 2
)

echo.
echo Materializing %TARGET%...
echo.

where py.exe >nul 2>nul
if %ERRORLEVEL%==0 (
    py -3 scripts\materialize_project.py "%TARGET%"
    set "RESULT=%ERRORLEVEL%"
) else (
    where python.exe >nul 2>nul
    if %ERRORLEVEL%==0 (
        python scripts\materialize_project.py "%TARGET%"
        set "RESULT=%ERRORLEVEL%"
    ) else (
        echo Python 3 was not found. Install Python or add python.exe/py.exe to PATH.
        set "RESULT=2"
    )
)

echo.
if not "%RESULT%"=="0" (
    echo Materialization failed with exit code %RESULT%.
) else (
    echo Done. The project is in build\materialized-projects\%TARGET%
)
echo.
pause
exit /b %RESULT%
