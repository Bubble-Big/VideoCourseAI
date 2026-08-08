@echo off
d:
cd /d D:\ClaudeProject\VideoCourseAI-main
docker-compose down
taskkill /f /im node.exe >nul 2>&1
