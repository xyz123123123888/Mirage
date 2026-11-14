@echo off
chcp 65001 >nul
REM Mirage Frontier - launcher
REM 按可用 Java 版本选择 fat jar：
REM   Java 11+ -> mirage.jar        (H2 2.x)
REM   Java 8   -> mirage-java8.jar  (H2 1.4.200)
REM mirage.jar 的字节码为 major 55 (Java 11)，因此 Java 11 及以上都能运行。
setlocal
cd /d "%~dp0"

REM 1) PATH 里的 java（最常见，版本由用户环境决定）
where java >nul 2>nul
if not errorlevel 1 (
    for /f "tokens=2 delims=." %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
        set "JV=%%v"
        goto :gotver
    )
)

REM 2) 回退到已知安装路径
set "JDK17=C:\Program Files\Java\jdk-17\bin\java.exe"
set "JDK8=C:\Program Files\Java\jre1.8.0_451\bin\java.exe"
if exist "%JDK17%" (
    echo [Java 17] Launching mirage.jar ...
    "%JDK17%" -jar mirage.jar
    goto :eof
)
if exist "%JDK8%" (
    echo [Java 8] Launching mirage-java8.jar ...
    "%JDK8%" -jar mirage-java8.jar
    goto :eof
)
echo [ERROR] No Java found. Install Java 8 or 11+.
exit /b 1

:gotver
REM JV 形如 "17" / "1" (对应 1.8) / "11" ...
if "%JV%"=="1" (
    echo [Java 8] Launching mirage-java8.jar ...
    java -jar mirage-java8.jar
    goto :eof
)
echo [Java %JV%] Launching mirage.jar ...
java -jar mirage.jar
endlocal
