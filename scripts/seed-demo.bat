@echo off
rem ===========================================================================
rem  Seed demo templates from docs\public\demo into a running server.
rem  Usage: scripts\seed-demo.bat [options]
rem  Help : node scripts\seed-demo.mjs --help
rem ===========================================================================
chcp 65001 >nul
setlocal

where node >nul 2>nul
if errorlevel 1 (
  echo [x] Node.js not found. Install Node 18+ and add it to PATH.
  exit /b 1
)

node "%~dp0seed-demo.mjs" %*
exit /b %errorlevel%
