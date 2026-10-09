@echo off
setlocal
set "REPOSITORY_ROOT=%~dp0"
set "GRADLE_ARGS=%*"
if "%~1"=="" set "GRADLE_ARGS=buildAndCollect"

echo ==^> Running old BuildCraft build: %GRADLE_ARGS%
pushd "%REPOSITORY_ROOT%builds\old"
call gradlew.bat %GRADLE_ARGS%
set "STATUS=%ERRORLEVEL%"
popd
if not "%STATUS%"=="0" exit /b %STATUS%

echo ==^> Running 1.21.X BuildCraft build: %GRADLE_ARGS%
pushd "%REPOSITORY_ROOT%builds\1.21.X"
call gradlew.bat %GRADLE_ARGS%
set "STATUS=%ERRORLEVEL%"
popd
if not "%STATUS%"=="0" exit /b %STATUS%

echo ==^> Running 26.X BuildCraft build: %GRADLE_ARGS%
pushd "%REPOSITORY_ROOT%builds\26.X"
call gradlew.bat %GRADLE_ARGS%
set "STATUS=%ERRORLEVEL%"
popd
exit /b %STATUS%
