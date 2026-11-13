@echo off
title Quest Modo Shooter
rem Pide permisos de administrador y abre el programa.
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0QuestModoShooter.ps1"
pause
