---
name: analyze-video
description: 走一遍完整的视频分析链路 —— 上传视频、触发 AI 总结、查看结果。
---

# 分析视频

引导用户上传视频并对其运行 AI 分析。

## 前置条件

- 开发环境需已启动（使用 `/start-dev`）。
- 用户需已登录（通过前端 http://localhost:5173 注册/登录）。

## 链路

1. **上传**：POST 到 `/media/upload`（本地文件）或 `/media/upload-url`（Bilibili/YouTube 链接）。记下返回的 media ID。

2. **触发 AI 分析**：`GET /debug/ai?id={mediaId}`。这是异步的 —— 写入 RocketMQ 后立即返回。

3. **监控**：前端每 3 秒轮询 `GET /media/list`。当 `aiSummary` 包含 `##`（Markdown 标题）时分析完成。

4. **查看**：结果以渲染后的 Markdown 显示在侧边栏面板中。转录文本单独提供。

## 已知陷阱

- 若 `aiSummary` 显示 `❌ AI 请求失败`，说明 AI API 调用失败且错误已写入数据库。用户无法从 UI 重试 —— 需在数据库中手动清空 `aiSummary` 以重新启用按钮。
- 分析可能需要数分钟。Redisson WatchDog 在长时间的 FFmpeg + AI 处理期间保持分布式锁存活。
