@echo off
rem CleanMyVR guided command line for Windows.
rem Uses CleanMyVR.exe next to this file (from the release zip); falls back to Python if you run from source.
setlocal
cd /d "%~dp0"
chcp 65001 >nul
if exist "%~dp0CleanMyVR.exe" (
  "%~dp0CleanMyVR.exe" --cli
  goto :end
)
where py >nul 2>nul && (cd app & py -3 -m cleanmyvr --cli & goto :end)
where python >nul 2>nul && (cd app & python -m cleanmyvr --cli & goto :end)
echo CleanMyVR.exe wasn't found next to this file, and Python isn't installed.
echo Download the Windows release zip, or install Python from https://www.python.org/downloads/
:end
pause
