netsh advfirewall firewall add rule name="StageMic" dir=in action=allow protocol=UDP localport=9876-9877 profile=any
Set-NetConnectionProfile -InterfaceAlias Wi-Fi -NetworkCategory Private
Write-Host "Firewall and Network configured successfully!" -ForegroundColor Green
Start-Sleep -Seconds 3
