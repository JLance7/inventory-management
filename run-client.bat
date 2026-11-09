@echo off
cd /d "%~dp0"
java -cp "build/classes;lib/*" inventory.DesktopApp
if errorlevel 1 pause
