@echo off
chcp 65001 >nul
REM Mirage Frontier - build wrapper
REM 实际构建逻辑在 build.ps1（单一真相源），这里只做转调。
REM   mirage.jar        -> Java 11+ (H2 2.x)   字节码 major 55
REM   mirage-java8.jar  -> Java 8  (H2 1.4.200) 字节码 major 52
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
if errorlevel 1 (echo   [FAIL] & exit /b 1)
endlocal
exit /b 0
