@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo   Kudu Console - build
echo ============================================

echo.
echo [1/4] Locating JDK...
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%i"
if not defined JAVA_EXE for /d %%d in ("C:\Program Files\Java\jdk-17*") do if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"
if not defined JAVA_EXE for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"
if not defined JAVA_EXE for /d %%d in ("%USERPROFILE%\.jdks\*") do if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"
if not defined JAVA_EXE goto :nojava
set "JAVA_HOME=%JAVA_EXE:\bin\java.exe=%"
echo       JAVA_HOME=%JAVA_HOME%

echo.
echo [2/4] Locating Maven...
set "MVN="
where mvn.cmd >nul 2>&1 && set "MVN=mvn.cmd"
if not defined MVN (
  for /d %%d in ("%USERPROFILE%\.m2\wrapper\dists\apache-maven-*") do (
    for /d %%e in ("%%d\*") do (
      if not defined MVN if exist "%%e\bin\mvn.cmd" set "MVN=%%e\bin\mvn.cmd"
    )
  )
)
if not defined MVN (
  for /d %%d in ("C:\Program Files\apache-maven*") do (
    if not defined MVN if exist "%%d\bin\mvn.cmd" set "MVN=%%d\bin\mvn.cmd"
  )
)
if not defined MVN goto :nomvn
echo       MVN=%MVN%

echo.
echo [3/4] Building frontend...
pushd web
if not exist node_modules (
  echo       npm install ...
  call npm install --no-audit --no-fund
  if errorlevel 1 goto :npmfail
)
call npm run build
if errorlevel 1 goto :npmfail
popd
if not exist "src\main\resources\static\index.html" goto :nofrontend

echo.
echo [4/4] Packaging backend...
call "%MVN%" -B -DskipTests clean package
if errorlevel 1 goto :mvnfail
if not exist "target\kudu-console.jar" goto :nojar

echo.
echo ============================================
echo   Build OK: target\kudu-console.jar
echo   Run: start.bat  (default port 8088)
echo ============================================
exit /b 0

:nojava
echo [ERROR] JDK 17 not found. Install JDK 17 or set JAVA_HOME.
exit /b 1
:nomvn
echo [ERROR] Maven not found. Install Maven or check %%USERPROFILE%%\.m2\wrapper\dists.
exit /b 1
:npmfail
popd
echo [ERROR] Frontend build failed. Check Node.js / npm.
exit /b 1
:nofrontend
echo [ERROR] Frontend did not produce src\main\resources\static\index.html.
exit /b 1
:mvnfail
echo [ERROR] Maven package failed. See log above.
exit /b 1
:nojar
echo [ERROR] target\kudu-console.jar was not created.
exit /b 1
