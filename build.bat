@echo off
rem Builds LastSpark.jar into .\target\ . Needs JDK 21+ and Maven on your PATH.
cd /d "%~dp0"
call mvn -q clean package
if errorlevel 1 (
  echo Build failed.
  exit /b 1
)
echo Done: target\LastSpark.jar - copy it into your server's plugins folder.
pause
