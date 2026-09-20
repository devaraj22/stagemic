@echo off
title StageMic Receiver
cd /d "%~dp0"
echo ==================================================
echo   Starting StageMic Receiver
echo ==================================================
py -u receiver.py
pause
