@echo off
setlocal
REM Build/sync helper for paths that contain spaces (e.g. "F:\To sort\...").
REM Routes Gradle project cache + AGP intermediates to C:\AndroidBuilds\barky.

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "CACHE_DIR=C:\AndroidBuilds\barky\gradle-cache"

if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"

cd /d "%~dp0"
call "%~dp0gradlew.bat" %* --project-cache-dir="%CACHE_DIR%"
exit /b %ERRORLEVEL%
