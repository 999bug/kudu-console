@echo off
setlocal
cd /d "%~dp0"

set "PORT=%~1"
if "%PORT%"=="" set "PORT=8088"

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%i"
if not defined JAVA_EXE for /d %%d in ("C:\Program Files\Java\jdk-17*") do if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"
if not defined JAVA_EXE for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"
if not defined JAVA_EXE for /d %%d in ("%USERPROFILE%\.jdks\*") do if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"
if not defined JAVA_EXE (
  echo [ERROR] JDK 17 not found. Install JDK 17 or set JAVA_HOME.
  pause
  exit /b 1
)

if not exist "target\kudu-console.jar" (
  echo target\kudu-console.jar not found, running build.bat ...
  call build.bat
  if errorlevel 1 (
    pause
    exit /b 1
  )
)

echo ============================================
echo   Kudu Console
echo   URL: http://localhost:%PORT%/
echo   Stop: Ctrl+C
echo ============================================

start "" /min cmd /c "timeout /t 4 >nul && start http://localhost:%PORT%/"

"%JAVA_EXE%" -jar "target\kudu-console.jar" --server.port=%PORT%

echo.
echo Server stopped.
pause
