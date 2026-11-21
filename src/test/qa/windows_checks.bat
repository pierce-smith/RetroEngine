@echo off
rem RetroEngine Windows checks. Double-click this file (or run it from a Command Prompt).
rem It builds the engine, runs the automated tests, then the GPU checks, and writes everything to
rem windows_checks_results.txt in the RetroEngine folder. Send that file back when it finishes.
rem Needs JDK 25 (Temurin). The Vulkan SDK is optional: without it the validation line says it is unavailable.
rem No PATH or JAVA_HOME setup needed: the script finds an installed JDK by itself, for this run only.

setlocal
cd /d "%~dp0..\..\.."
set "OUT=%CD%\windows_checks_results.txt"
set "RE=build\install\retro-engine\bin\retro-engine.bat"
set "PROJ=src\test\resources\TestProject"

echo RetroEngine Windows checks > "%OUT%"
echo Started %DATE% %TIME% >> "%OUT%"
ver >> "%OUT%"

call :find_jdk
if not defined FOUND_JDK goto :no_java
set "JAVA_HOME=%FOUND_JDK%"
set "PATH=%FOUND_JDK%\bin;%PATH%"
echo Using Java from %JAVA_HOME%
echo Using Java from %JAVA_HOME% >> "%OUT%"
"%JAVA_HOME%\bin\java.exe" -version >> "%OUT%" 2>&1
wmic path win32_VideoController get name,driverversion >> "%OUT%" 2>&1

echo.
echo === 1 of 5: Building the engine (a few minutes the first time) ===
echo. >> "%OUT%"
echo ===== 1. BUILD ===== >> "%OUT%"
call gradlew.bat installDist --console=plain >> "%OUT%" 2>&1
set "BUILD_EXIT=%ERRORLEVEL%"
if not "%BUILD_EXIT%"=="0" (
    echo The build failed. Nothing else can run. Send windows_checks_results.txt back.
    echo BUILD FAILED, exit code %BUILD_EXIT% >> "%OUT%"
    goto :summary
)

echo === 2 of 5: Automated tests (about a minute, no windows open) ===
echo. >> "%OUT%"
echo ===== 2. AUTOMATED TESTS ===== >> "%OUT%"
call gradlew.bat test --console=plain >> "%OUT%" 2>&1
set "TEST_EXIT=%ERRORLEVEL%"

echo === 3 of 5: 1,000-mesh benchmark (a window opens for 40 seconds; don't touch it) ===
echo. >> "%OUT%"
echo ===== 3. BENCHMARK BenchStatic ===== >> "%OUT%"
call "%RE%" --game --project "%PROJ%" --scene Assets/Scenes/BenchStatic.rscene --bench 30 --bench-warmup 10 >> "%OUT%" 2>&1
set "BENCH_EXIT=%ERRORLEVEL%"

echo === 4 of 5: Render check with GPU validation (a window opens for 15 seconds; look at it) ===
echo     It should show a checkered floor, a 40 x 40 grid of orange cubes and two arms floating above them.
echo. >> "%OUT%"
echo ===== 4. RENDER CHECK with --gpu-debug ===== >> "%OUT%"
call "%RE%" --game --project "%PROJ%" --scene Assets/Scenes/RenderCheck.rscene --bench 10 --bench-warmup 5 --gpu-debug >> "%OUT%" 2>&1
set "RENDER_EXIT=%ERRORLEVEL%"

echo === 5 of 5: Simulated GPU loss ===
echo     After about 5 seconds a "graphics card stopped responding" message appears. Click OK.
echo. >> "%OUT%"
echo ===== 5. SIMULATED GPU LOSS ===== >> "%OUT%"
call "%RE%" --game --project "%PROJ%" --scene Assets/Scenes/RenderCheck.rscene --simulate-gpu-loss 300 >> "%OUT%" 2>&1
set "LOST_EXIT=%ERRORLEVEL%"

:summary
echo. >> "%OUT%"
echo ===== SUMMARY ===== >> "%OUT%"
echo build exit=%BUILD_EXIT% (0 = ok) >> "%OUT%"
echo tests exit=%TEST_EXIT% (0 = all passed) >> "%OUT%"
echo benchmark exit=%BENCH_EXIT% (0 = ok) >> "%OUT%"
echo render check exit=%RENDER_EXIT% (0 = ok) >> "%OUT%"
echo gpu loss exit=%LOST_EXIT% (4 = expected) >> "%OUT%"
findstr /L /C:"tests completed" /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" /C:"[Bench]" /C:"[VulkanMemory]" /C:"Validation finished" /C:"[GpuDebug]" /C:"GPU device lost" /C:"FATAL" "%OUT%" > "%OUT%.summary" 2>nul
type "%OUT%.summary" >> "%OUT%"
del "%OUT%.summary" 2>nul
echo Finished %DATE% %TIME% >> "%OUT%"

echo.
echo Done. Results are in:
echo     %OUT%
echo Send that file back.
echo.
echo One check is by hand: open the editor (%RE%), open a COPY of %PROJ%,
echo choose File, Export Standalone Build, run the .exe it makes, and note whether it opens with the game's name
echo in the title bar and writes a log to %%LOCALAPPDATA%%\RetroEngine\logs\
echo.
pause
exit /b 0

:no_java
echo Could not find Java. Install the Temurin JDK 25 (adoptium.net, the .msi installer) and run this again.
echo JAVA NOT FOUND: no JDK in Program Files, JAVA_HOME or PATH >> "%OUT%"
pause
exit /b 1

rem Finds a JDK folder (one with bin\java.exe and a release file) and puts it in FOUND_JDK.
rem Order: a JDK 25 in the usual install folders, then JAVA_HOME, then java on PATH.
:find_jdk
set "FOUND_JDK="
for %%R in ("%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Java" "%ProgramFiles%\Zulu" "%ProgramFiles%\Amazon Corretto") do (
    for /d %%J in ("%%~R\jdk-25*" "%%~R\jdk25*" "%%~R\zulu-25*" "%%~R\zulu25*") do (
        if exist "%%~J\bin\java.exe" if exist "%%~J\release" set "FOUND_JDK=%%~J"
    )
)
if defined FOUND_JDK goto :eof
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" if exist "%JAVA_HOME%\release" set "FOUND_JDK=%JAVA_HOME%"
if defined FOUND_JDK goto :eof
for /f "delims=" %%P in ('where java 2^>nul') do (
    if not defined FOUND_JDK if exist "%%~dpP..\release" set "FOUND_JDK=%%~dpP.."
)
goto :eof
