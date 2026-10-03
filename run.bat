@echo off
chcp 65001 >nul
cd /d %~dp0
echo ========================================
echo   试卷电子化 exam2doc  启动中...
echo   电脑访问   http://127.0.0.1:8484
echo   手机访问   http://本机IP:8484 （同一WiFi，App 自动发现）
echo   关闭本窗口即停止服务
echo ========================================
netsh advfirewall firewall add rule name="exam2doc" dir=in action=allow protocol=TCP localport=8484 >nul 2>&1
start "" http://127.0.0.1:8484
.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8484
pause
