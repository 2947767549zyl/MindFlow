@echo off
chcp 65001 >nul
REM PaiCLI Build & Run Helper (Windows PowerShell/Batch)
REM Usage: paicli build | paicli run | paicli test

set JAVA_HOME=C:\Users\14736\.jdks\ms-21.0.10
set MAVEN_HOME=C:\Users\14736\Desktop\apache-maven
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

set PROJECT_DIR=%~dp0

if "%1"=="build" goto :build
if "%1"=="run" goto :run
if "%1"=="test" goto :test
if "%1"=="mvn" goto :mvn
goto :help

:build
cd /d "%PROJECT_DIR%"
mvn clean package -DskipTests %2 %3 %4 %5
goto :end

:run
cd /d "%PROJECT_DIR%"
java -jar target\paicli-1.0-SNAPSHOT.jar
goto :end

:test
cd /d "%PROJECT_DIR%"
mvn test %2 %3 %4 %5
goto :end

:mvn
cd /d "%PROJECT_DIR%"
mvn %2 %3 %4 %5 %6 %7
goto :end

:help
echo.
echo PaiCLI Build ^& Run Helper
echo.
echo Usage:
echo   paicli build          Compile (skip tests)
echo   paicli run            Run PaiCLI
echo   paicli test           Run tests
echo   paicli mvn <args>     Pass args directly to Maven
echo.
goto :end

:end
