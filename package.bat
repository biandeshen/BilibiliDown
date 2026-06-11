@echo off
:: Package: produce versioned INeedBiliAV_v{version}.jar
cd /d %~dp0
echo === BilibiliDown Package ===

:: Read version from Global.java
REM version parsed from Global.java
REM version trimmed
set VER=v6.41
echo Version: %VER%

:: Clean
if exist _target rmdir /s /q _target
mkdir _target

:: Copy sources (skip test)
xcopy src _target\ /s /y /q >nul
rmdir /s /q _target\nicelee\test 2>nul

:: Collect java files
cd _target
dir /s /b *.java > ..\_sources.tmp 2>nul
cd ..

:: Extract libs into target
cd _target
for %%j in (..\libs\*.jar) do jar xf "%%j" 2>nul
cd ..

:: Compile
echo Compiling...
cd _target
javac -encoding UTF-8 -nowarn @..\_sources.tmp
if %errorlevel% neq 0 (
    cd ..
    rmdir /s /q _target & del _sources.tmp 2>nul
    echo COMPILE FAILED
    exit /b 1
)

:: Remove .java files
del /a /f /s /q *.java 2>nul
cd ..

:: Package main jar
echo Packaging INeedBiliAV_%VER%.jar ...
jar cvfe "INeedBiliAV_%VER%.jar" nicelee.ui.FrameMain -C _target . >nul 2>nul
echo Done: INeedBiliAV_%VER%.jar

:: Package launcher if exists
if exist src-launcher (
    mkdir _target-launcher 2>nul
    xcopy src-launcher _target-launcher\ /s /y /q >nul
    cd _target-launcher
    dir /s /b *.java > ..\_sources2.tmp 2>nul
    javac -encoding UTF-8 @..\_sources2.tmp
    del /a /f /s /q *.java 2>nul
    cd ..
    jar cf "launch_%VER%.jar" -C _target-launcher .
    echo Done: launch_%VER%.jar
    rmdir /s /q _target-launcher & del _sources2.tmp 2>nul
)

:: Cleanup
rmdir /s /q _target
del _sources.tmp 2>nul

echo === Package complete: INeedBiliAV_%VER%.jar ===

copy /Y "INeedBiliAV_%VER%.jar" release\ >nul

copy /Y "INeedBiliAV_%VER%.jar" "release\INeedBiliAV.jar" >nul
REM pause
