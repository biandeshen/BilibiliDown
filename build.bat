@echo off
:: Quick Local Build: compile only, run from bin/
cd /d %~dp0
echo === BilibiliDown Dev Build ===

if exist bin rmdir /s /q bin
mkdir bin

:: Collect all java files, exclude test
dir /s /b src\nicelee\bilibili\*.java > _sources.tmp 2>nul
dir /s /b src\nicelee\ui\*.java >> _sources.tmp 2>nul
dir /s /b src\nicelee\server\*.java >> _sources.tmp 2>nul
dir /s /b src\org\*.java >> _sources.tmp 2>nul

:: Compile
echo Compiling...
javac -encoding UTF-8 -cp "libs\*" -d bin @_sources.tmp
if %errorlevel% neq 0 (
    del _sources.tmp 2>nul
    echo COMPILE FAILED
    exit /b 1
)

:: Copy resources
if exist src\resources xcopy src\resources bin\resources\ /s /y /q >nul 2>nul

del _sources.tmp 2>nul
echo === Build OK: bin/ ===
echo Run: java -cp "bin;libs\*" nicelee.ui.FrameMain
