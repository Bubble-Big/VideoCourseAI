---
name: start-dev
description: 启动完整开发环境 —— Docker 中间件、后端、前端 —— 用于 VideoCourseAI。
---

# 启动开发环境

启动本地开发 VideoCourseAI 所需的全部服务。

## 步骤

1. 启动 Docker 中间件（若尚未运行）：
   ```bash
   docker-compose up -d
   ```
   等待 MySQL、Redis、MinIO、RocketMQ 健康就绪（约 30 秒）。

2. 启动 Spring Boot 后端（终端 1）：
   ```bash
   cd server && mvn clean spring-boot:run
   ```
   确认：`Started VideoCourseAIApplication in X.XXX seconds`，端口 9090。

3. 启动 Vue 前端（终端 2）：
   ```bash
   cd client && npm install && npm run dev
   ```
   打开 http://localhost:5173。

## 验证

- 后端健康检查：`curl http://localhost:9090/media/list`
- RocketMQ 控制台：http://localhost:8180
- MinIO 控制台：http://localhost:9001（minioadmin / minioadmin）
