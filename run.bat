@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "target\pomosh-aktivistu-0.1.0.jar" (
  echo JAR not found, running mvn package ...
  call mvn package
  if errorlevel 1 exit /b 1
)
java -jar "target\pomosh-aktivistu-0.1.0.jar"
pause
