@echo off
cd /d "%~dp0"
java -cp "build/classes;lib/*" inventory.Server
if errorlevel 1 pause
