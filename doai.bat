@echo off
d:
cd D:\ClaudeProject\VideoCourseAI-main
docker-compose up -d
cd client
npm run dev