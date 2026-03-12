@echo off
REM ============================================================================
REM build_llama_opencl.bat
REM
REM Windows wrapper script to build llama.cpp with OpenCL for Android.
REM Requires WSL (Windows Subsystem for Linux) with Ubuntu installed.
REM
REM Prerequisites:
REM   - WSL with Ubuntu 22.04+ installed
REM   - Android NDK installed in WSL or accessible via /mnt/c/
REM   - Run: wsl --install Ubuntu (if not already installed)
REM
REM Usage:
REM   build_llama_opencl.bat
REM
REM Author: STELLiQ Engineering
REM Version: 1.0.0
REM ============================================================================

echo.
echo ========================================================================
echo  llama.cpp OpenCL Build for Android (Windows WSL Wrapper)
echo ========================================================================
echo.

REM Check if WSL is available
wsl --status >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] WSL is not installed or not available.
    echo.
    echo Please install WSL with Ubuntu:
    echo   1. Open PowerShell as Administrator
    echo   2. Run: wsl --install -d Ubuntu
    echo   3. Restart your computer
    echo   4. Run this script again
    echo.
    pause
    exit /b 1
)

REM Get the directory where this script is located
set SCRIPT_DIR=%~dp0

REM Convert Windows path to WSL path
for /f "tokens=*" %%i in ('wsl wslpath -u "%SCRIPT_DIR%"') do set WSL_SCRIPT_DIR=%%i

echo [INFO] Running build script in WSL...
echo [INFO] Script directory: %WSL_SCRIPT_DIR%
echo.

REM Install prerequisites in WSL if needed and run the build script
wsl bash -c "cd '%WSL_SCRIPT_DIR%' && sudo apt-get update && sudo apt-get install -y git cmake ninja-build && chmod +x build_llama_opencl.sh && ./build_llama_opencl.sh"

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================================
    echo  BUILD COMPLETE!
    echo ========================================================================
    echo.
    echo Output libraries are in: %SCRIPT_DIR%output\arm64-v8a\
    echo.
    echo Copy them to: app\src\main\jniLibs\arm64-v8a\
    echo.
) else (
    echo.
    echo [ERROR] Build failed. Check the output above for errors.
    echo.
)

pause
