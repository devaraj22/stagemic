@echo off
setlocal
cd /d "%~dp0"

py -c "import aiohttp, numpy, sounddevice" >nul 2>&1
if errorlevel 1 (
  echo Installing browser microphone receiver requirements...
  py -m pip install -r web_mic_requirements.txt
  if errorlevel 1 goto :requirements_failed
)

where cloudflared >nul 2>&1
if errorlevel 1 goto :cloudflared_missing

start "StageMic Browser Receiver" /D "%~dp0" "%ComSpec%" /k py -u "%~dp0web_mic_receiver.py"
timeout /t 3 /nobreak >nul
echo.
echo Starting an HTTPS tunnel. Copy the https://...trycloudflare.com link below
echo and open it in your phone browser. Keep both windows open while using it.
echo.
cloudflared tunnel --url http://127.0.0.1:8765
goto :end

:cloudflared_missing
echo.
echo cloudflared is not installed yet.
echo Install Cloudflare Tunnel, then run this file again:
echo https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
echo.
pause
goto :end

:requirements_failed
echo.
echo Could not install the required Python packages. Check your Python and internet connection.
pause

:end
endlocal
