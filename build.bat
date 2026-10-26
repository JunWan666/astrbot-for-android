@echo off
setlocal
chcp 65001 >nul
title AstrBot 正式版一键构建
python -B -X utf8 "%~dp0tools\build_release.py" %*
set "build_exit=%ERRORLEVEL%"
if not "%ASTRBOT_BUILD_NO_PAUSE%"=="1" pause
exit /b %build_exit%
