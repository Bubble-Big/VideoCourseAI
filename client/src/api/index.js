// 后端接口统一封装：集中 BASE_URL 与所有请求，杜绝 URL 硬编码散落各处。

const BASE_URL = 'http://localhost:9090'

// ---- 链接上传 ----
export function uploadUrl(formData) {
  return fetch(`${BASE_URL}/media/upload-url`, { method: 'POST', body: formData })
}

// ---- 媒体列表（带时间戳防缓存） ----
export function getMediaList(userId) {
  let url = `${BASE_URL}/media/list`
  if (userId) {
    const timestamp = new Date().getTime()
    url += `?userId=${userId}&_t=${timestamp}`
  }
  return fetch(url)
}

// ---- 删除媒体 ----
export function deleteMedia(id, userId) {
  let url = `${BASE_URL}/media/delete?id=${id}`
  if (userId) url += `&userId=${userId}`
  return fetch(url, { method: 'DELETE' })
}

// ---- 下载音频 ----
export function downloadAudio(id) {
  return fetch(`${BASE_URL}/debug/download?id=${id}`)
}

// ---- 提取文字 ----
export function transcribe(id) {
  return fetch(`${BASE_URL}/debug/transcribe?id=${id}`)
}

// ---- AI 智能总结 ----
export function aiAnalyze(id) {
  return fetch(`${BASE_URL}/debug/ai?id=${id}`)
}

// ---- 用户登录 / 注册 ----
export function login(payload) {
  return fetch(`${BASE_URL}/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function register(payload) {
  return fetch(`${BASE_URL}/user/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

// ---- 分片上传相关接口 ----

export function chunkInit(payload, signal) {
  return fetch(`${BASE_URL}/media/api/chunk/init`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal,
  })
}

export function chunkUpload(formData, signal) {
  return fetch(`${BASE_URL}/media/api/chunk/upload`, {
    method: 'POST',
    body: formData,
    signal,
  })
}

export function chunkMerge(payload) {
  return fetch(`${BASE_URL}/media/api/chunk/merge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function chunkCheck(payload) {
  return fetch(`${BASE_URL}/media/api/chunk/check`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function chunkCancel(payload) {
  return fetch(`${BASE_URL}/media/api/chunk/cancel`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

// ---- SSE 任务事件 ----
// 返回端点 URL 字符串（供 EventSource 使用，不发起 fetch）
export function getTaskEventsUrl(id, type) {
  return `${BASE_URL}/debug/task-events?id=${id}&type=${type}`
}
