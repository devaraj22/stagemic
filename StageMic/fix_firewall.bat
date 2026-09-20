@echo off
:: Self-elevating batch script to configure Windows Firewall for StageMic
>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"
if '%errorlevel%' NEQ '0' (
    echo Requesting administrative privileges...
    echo Set UAC = CreateObject^("Shell.Application"^) > "%temp%\getadmin.vbs"
    echo UAC.ShellExecute "cmd.exe", "/c """"%~f0""""", "", "runas", 1 >> "%temp%\getadmin.vbs"
    "%temp%\getadmin.vbs"
    del "%temp%\getadmin.vbs"
    exit /B
)

cd /d "%~dp0"
echo ==================================================
echo   Configuring Windows Firewall for StageMic
echo ==================================================
netsh advfirewall firewall delete rule name="StageMic" >nul 2>&1
netsh advfirewall firewall add rule name="StageMic" dir=in action=allow protocol=UDP localport=9876-9877 profile=any
powershell -Command "Set-NetConnectionProfile -InterfaceAlias Wi-Fi -NetworkCategory Private -ErrorAction SilentlyContinue"
echo.
echo ==================================================
echo   SUCCESS: Firewall rule added & Wi-Fi set to Private!
echo ==================================================
echo You can now connect from your StageMic phone app.
echo.
pause
