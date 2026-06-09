@echo off
chcp 65001 >nul
echo =============================
echo   Tomato Novel Downloader
echo   Build Script
echo =============================
echo.

call gradle jar

if %errorlevel% equ 0 (
    echo [OK] build/libs/fq_download.jar
) else (
    echo [FAILED]
)
echo.
pause >nul
