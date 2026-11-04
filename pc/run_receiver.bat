@echo off
setlocal
cd /d "%~dp0"

if exist PhoneBridgeReceiver.exe (
  PhoneBridgeReceiver.exe
  if errorlevel 1 goto failed
  exit /b 0
)

where py >nul 2>nul
if errorlevel 1 (
  echo PhoneBridgeReceiver.exe is missing. To run from source, install Python from https://www.python.org/downloads/windows/ and enable the Python launcher.
  pause
  exit /b 1
)

if not exist .venv\Scripts\python.exe (
  py -3 -m venv .venv
  if errorlevel 1 goto failed
)

set VGAMEPAD_SKIP_VIGEMBUS_INSTALL=true
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto failed

.venv\Scripts\python.exe phonebridge_receiver.py
if errorlevel 1 goto failed
exit /b 0

:failed
echo.
echo PhoneBridge could not start. Check the message above, then try again.
pause
exit /b 1
