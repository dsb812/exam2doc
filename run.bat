@echo off
chcp 65001 >nul
cd /d %~dp0
echo ========================================
echo   试卷电子化 exam2doc  启动中...
echo   浏览器访问 http://127.0.0.1:8484
echo   关闭本窗口即停止服务
echo ========================================
start "" http://127.0.0.1:8484
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8484
pause
