@echo off
rem Startet MageLite (Desktop). VS Code setzt ELECTRON_RUN_AS_NODE - das muss weg.
set ELECTRON_RUN_AS_NODE=
cd /d "%~dp0desktop"
if not exist node_modules\.bin\electron.cmd (
  echo Electron fehlt - bitte einmal scripts\build.ps1 ausfuehren.
  pause
  exit /b 1
)
start "" node_modules\.bin\electron.cmd .
