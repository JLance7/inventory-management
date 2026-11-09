@echo off
cd /d "%~dp0"
java -cp "inventory-server.jar;lib/*" inventory.Server
if errorlevel 1 pause
