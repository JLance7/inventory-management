@echo off
cd /d "%~dp0"
java -cp "inventory-client.jar;lib/*" inventory.DesktopApp
if errorlevel 1 pause
