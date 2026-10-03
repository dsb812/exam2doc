@echo off
chcp 65001 >nul
cd /d %~dp0
REM 构建 Android APK（需要 tools/ 下已就位 JDK17、Gradle 8.7、android-sdk）
set JAVA_HOME=%~dp0tools\jdk-17.0.2
set ANDROID_SDK_ROOT=%~dp0tools\android-sdk
set JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897

cd mobile
call ..\tools\gradle-8.7\bin\gradle.bat assembleRelease %*
if errorlevel 1 ( echo 构建失败 & pause & exit /b 1 )
cd ..
if not exist work\apk mkdir work\apk
copy /y mobile\app\build\outputs\apk\release\app-release.apk work\apk\exam2doc.apk >nul
echo.
echo 构建完成: work\apk\exam2doc.apk
echo 手机端「检查更新」或网页 http://127.0.0.1:8484/download/apk 可获取
pause
