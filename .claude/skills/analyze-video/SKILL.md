---
name: analyze-video
description: Walk through the complete video analysis pipeline — upload a video, trigger AI summarization, and view the result.
---

# Analyze Video

Guides the user through uploading a video and running AI analysis on it.

## Prerequisites

- Dev environment must be running (use `/start-dev`).
- User must be logged in (register/login via the frontend at http://localhost:5173).

## Pipeline

1. **Upload**: POST to `/media/upload` (local file) or `/media/upload-url` (Bilibili/YouTube link). Note the returned media ID.

2. **Trigger AI Analysis**: `GET /debug/ai?id={mediaId}`. This is async — returns immediately after enqueuing to RocketMQ.

3. **Monitor**: The frontend polls `GET /media/list` every 3 seconds. Analysis is complete when `aiSummary` contains `##` (Markdown heading).

4. **View**: Results display in the sidebar panel as rendered Markdown. Transcript text is available separately.

## Gotchas

- If `aiSummary` shows `❌ AI 请求失败`, the AI API call failed and the error was written to the database. The user cannot retry from the UI — manually clear `aiSummary` in the DB to re-enable the button.
- Analysis may take several minutes. The Redisson WatchDog keeps the distributed lock alive during long FFmpeg + AI processing.
