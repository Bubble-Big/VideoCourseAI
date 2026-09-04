---
name: start-dev
description: 启动完整开发环境 —— Docker 中间件、后端、前端 —— 用于 VideoCourseAI。
---

# 启动开发环境

启动本地开发 VideoCourseAI 所需的全部服务。

## 快速启动与停止（Windows 推荐）

项目中存在一键启动、一键停止脚本，优先调用脚本快速进行启动与终止。

**启动服务**：`cmd //c "c:\Users\74726\project\VideoCourseAI\start-all.bat"`（一键启动 Docker + 后端 + 前端）
**停止服务**：`cmd //c "c:\Users\74726\project\VideoCourseAI\stop-all.bat"`（一键停止，自动终止 9090/5173 端口进程 + 停止 Docker）

> Git Bash 中调用 .bat 必须用 `cmd //c "绝对路径"`（双斜杠+绝对路径），否则 `/c` 会被当成路径分隔符，脚本不会真正执行但仍返回退出码 0。

---

## 手动启动步骤

1. 启动 Docker 中间件（若尚未运行）：
   ```bash
   docker-compose up -d
   ```
   等待 MySQL、Redis、MinIO、RocketMQ 健康就绪（约 30 秒）。

2. 启动 Spring Boot 后端（终端 1）：
   ```bash
   cd server && mvn spring-boot:run
   ```
   确认：`Started VideoCourseAIApplication in X.XXX seconds` + `The following 1 profile is active: "local"`，端口 9090。

3. 启动 Vue 前端（终端 2）：
   ```bash
   cd client && npm install && npm run dev
   ```
   打开 http://localhost:5173。

## 验证

- 前端应用：http://localhost:5173
- 后端健康检查：`curl http://localhost:9090/media/list`
- RocketMQ 控制台：http://localhost:8180
- MinIO 控制台：http://localhost:9001（minioadmin / minioadmin）
