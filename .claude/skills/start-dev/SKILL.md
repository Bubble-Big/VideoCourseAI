---
name: start-dev
description: Start the full development environment — Docker middleware, backend, and frontend — for VideoCourseAI.
---

# Start Dev Environment

Launch all services needed to work on VideoCourseAI locally.

## Steps

1. Start Docker middleware (if not already running):
   ```bash
   docker-compose up -d
   ```
   Wait for MySQL, Redis, MinIO, RocketMQ to be healthy (~30s).

2. Start the Spring Boot backend (terminal 1):
   ```bash
   cd server && mvn clean spring-boot:run
   ```
   Confirm: `Started VideoCourseAIApplication in X.XXX seconds` on port 9090.

3. Start the Vue frontend (terminal 2):
   ```bash
   cd client && npm install && npm run dev
   ```
   Opens on http://localhost:5173.

## Verify

- Backend health: `curl http://localhost:9090/media/list`
- RocketMQ console: http://localhost:8180
- MinIO console: http://localhost:9001 (minioadmin / minioadmin)
