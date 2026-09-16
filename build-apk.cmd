@echo off
REM ===========================================================================
REM  FFmpegX 一键构建 APK（Windows 双击即可）
REM
REM  实际逻辑在 scripts/build-apk.sh 里，这里只负责找到 bash 并转交参数。
REM  可以直接把参数透传进来，例如：
REM      build-apk.cmd --release
REM      build-apk.cmd --abis arm64-v8a
REM ===========================================================================
setlocal
cd /d "%~dp0"

REM ---- 找 bash ----
set "BASH_EXE="
where bash >nul 2>&1 && set "BASH_EXE=bash"
if not defined BASH_EXE if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH_EXE=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"

if not defined BASH_EXE (
    echo.
    echo   [错误] 找不到 bash.exe
    echo.
    echo   本项目需要 Git for Windows 提供的 bash 来运行构建脚本。
    echo   下载地址： https://git-scm.com/download/win
    echo.
    pause
    exit /b 1
)

REM ---- 检查 java ----
if not defined JAVA_HOME (
    where java >nul 2>&1
    if errorlevel 1 (
        echo.
        echo   [错误] 找不到 java，也没有设置 JAVA_HOME
        echo.
        echo   请安装 JDK 17 或更高版本： https://adoptium.net/
        echo.
        pause
        exit /b 1
    )
)

echo.
echo   FFmpegX 构建脚本
echo   项目目录: %CD%
echo   bash:     %BASH_EXE%
echo.

"%BASH_EXE%" "scripts/build-apk.sh" %*

echo.
pause
