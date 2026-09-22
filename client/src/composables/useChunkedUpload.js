import { ref } from 'vue'
import { chunkInit, chunkUpload, chunkMerge, chunkCheck, chunkCancel } from '../api/index.js'

const CHUNK_SIZE = 5 * 1024 * 1024        // 5MB 每片
const MAX_CONCURRENCY = 3                  // 最大并发数
const MAX_RETRIES = 3                      // 每片最多重试次数

const LS_KEY = 'chunked_uploads'           // localStorage 键名（复数，支持多文件记录）

// ---- 模块级状态（单例） ----
const uploadState = ref({
  status: 'idle',          // idle | uploading | merging | done | error | cancelled
  uploadId: null,
  fileName: '',
  fileSize: 0,
  totalChunks: 0,
  completedChunks: new Set(),
  progress: 0,             // 0 ~ 100
  uploadedBytes: 0,
  speed: 0,                // 字节/秒
  error: null,
  mediaId: null,
  filePath: null,
  duplicateMediaId: null,  // 去重提示
  pendingFile: null,       // 场景一：中断后暂存的 File 引用，用于一键继续
})

// 内部非响应式变量
let abortController = null
let speedSamples = []       // 速度采样窗口 [{time, bytes}]
let currentFile = null      // 当前正在上传的 File 引用

/**
 * 生成文件指纹：文件名 + 大小 + 最后修改时间
 * 同一文件重新选择时，指纹不变，可用于匹配历史上传会话
 */
function computeFileKey(file) {
  return `${file.name}_${file.size}_${file.lastModified}`
}

// ---- localStorage 读写（支持多文件记录） ----

function loadAllSessions() {
  try {
    const raw = localStorage.getItem(LS_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function saveSession(fileKey, data) {
  const all = loadAllSessions()
  all[fileKey] = { ...data, timestamp: Date.now() }
  localStorage.setItem(LS_KEY, JSON.stringify(all))
}

function removeSession(fileKey) {
  const all = loadAllSessions()
  delete all[fileKey]
  localStorage.setItem(LS_KEY, JSON.stringify(all))
}

function findSession(fileKey) {
  const all = loadAllSessions()
  const data = all[fileKey]
  if (!data) return null
  // 48 小时内有效
  if (Date.now() - data.timestamp > 48 * 3600 * 1000) {
    delete all[fileKey]
    localStorage.setItem(LS_KEY, JSON.stringify(all))
    return null
  }
  return data
}

// ---- 速度计算 ----

function updateSpeed(chunkBytes) {
  const now = Date.now()
  speedSamples.push({ time: now, bytes: chunkBytes })
  speedSamples = speedSamples.filter(s => now - s.time < 5000)
  const totalBytes = speedSamples.reduce((sum, s) => sum + s.bytes, 0)
  const elapsed = (now - speedSamples[0].time) / 1000
  uploadState.value.speed = elapsed > 0 ? totalBytes / elapsed : 0
}

// ---- 场景二：根据文件匹配已有的上传会话 ----

/**
 * 用文件指纹匹配 localStorage 中的历史上传记录，
 * 并调后端 check 接口获取最新状态。
 * @returns { fileKey, uploadId, totalChunks, completedChunks, fileName, fileSize } 或 null
 */
async function matchUpload(file) {
  const fileKey = computeFileKey(file)
  const saved = findSession(fileKey)
  if (!saved) return null

  try {
    const resp = await chunkCheck({ uploadId: saved.uploadId })

    if (!resp.ok) {
      // check 失败（uploadId 可能已过期），清理记录
      removeSession(fileKey)
      return null
    }

    const result = await resp.json()
    const data = result.data

    if (!data || data.status === 'NOT_FOUND') {
      removeSession(fileKey)
      return null
    }

    if (data.status === 'COMPLETED') {
      // 已经合并完成了，不需要恢复
      removeSession(fileKey)
      return null
    }

    // UPLOADING → 可以恢复
    return {
      fileKey,
      uploadId: saved.uploadId,
      totalChunks: saved.totalChunks,
      completedChunks: new Set(data.completedChunks || []),
      fileName: saved.fileName,
      fileSize: saved.fileSize,
    }
  } catch {
    // 网络异常，返回 null
    return null
  }
}

// ---- 主流程 ----

/**
 * 入口：开始上传一个文件（自动检测是否可续传）
 * @param {File} file  浏览器 File 对象
 * @param {number} userId
 * @param {object|null} resumeFrom  matchUpload() 的返回值，非 null 表示断点续传
 */
async function startUpload(file, userId, resumeFrom = null, force = false) {
  if (!file) return

  currentFile = file
  abortController = new AbortController()
  speedSamples = []

  const fileKey = computeFileKey(file)
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
  let uploadId, completedSet

  uploadState.value = {
    ...uploadState.value,
    status: 'uploading',
    fileName: file.name,
    fileSize: file.size,
    totalChunks,
    completedChunks: new Set(),
    progress: 0,
    uploadedBytes: 0,
    speed: 0,
    error: null,
    duplicateMediaId: null,
    pendingFile: null,
  }

  try {
    if (resumeFrom) {
      // ========== 断点续传路径 ==========
      uploadId = resumeFrom.uploadId
      completedSet = resumeFrom.completedChunks

      uploadState.value.uploadId = uploadId
      uploadState.value.completedChunks = completedSet
      uploadState.value.progress = Math.round((completedSet.size / totalChunks) * 100)
      uploadState.value.uploadedBytes = completedSet.size * CHUNK_SIZE
    } else {
      // ========== 全新上传路径 ==========
      const initResp = await chunkInit(
        { fileName: file.name, fileSize: file.size, totalChunks, userId, force },
        abortController.signal,
      )

      if (!initResp.ok) {
        throw new Error('初始化失败: ' + await initResp.text())
      }

      const initResult = await initResp.json()
      const initData = initResult.data

      if (initData.status === 'HINT_DUPLICATE') {
        uploadState.value.status = 'idle'
        uploadState.value.duplicateMediaId = initData.existingMediaId
        currentFile = null
        return
      }

      uploadId = initData.uploadId
      completedSet = new Set(initData.completedChunks || [])

      uploadState.value.uploadId = uploadId
      uploadState.value.completedChunks = completedSet
      uploadState.value.uploadedBytes = completedSet.size * CHUNK_SIZE
      uploadState.value.progress = Math.round((completedSet.size / totalChunks) * 100)

      // 首次持久化
      saveSession(fileKey, { uploadId, fileName: file.name, fileSize: file.size, totalChunks })
    }

    // 2. 上传剩余分片
    await uploadAllChunks(uploadId, file, totalChunks, completedSet, fileKey)

    // 3. 合并
    uploadState.value.status = 'merging'
    const mergeResp = await chunkMerge({ uploadId, userId })

    if (mergeResp.status === 409) {
      throw new Error('合并正在进行中，请稍后刷新页面查看')
    }
    if (!mergeResp.ok) {
      throw new Error('合并失败: ' + await mergeResp.text())
    }

    const mergeResult = await mergeResp.json()
    const mergeData = mergeResult.data
    uploadState.value.status = 'done'
    uploadState.value.progress = 100
    uploadState.value.mediaId = mergeData.mediaId
    uploadState.value.filePath = mergeData.filePath

    removeSession(fileKey)
    currentFile = null
  } catch (err) {
    if (err.name === 'AbortError') {
      uploadState.value.status = 'cancelled'
      // 场景一：中断后 File 仍在内存，暂存供一键继续
      if (currentFile) {
        uploadState.value.pendingFile = currentFile
      }
    } else {
      uploadState.value.status = 'error'
      uploadState.value.error = err.message
      // 错误也暂存，允许重试（场景一）
      if (currentFile) {
        uploadState.value.pendingFile = currentFile
      }
    }
  }
}

/**
 * 场景一专用：一键继续上传（File 对象仍在内存，跳过 init/check，直接续传）
 */
async function resumeWithoutRecheck(file, userId) {
  if (!file) return
  const fileKey = computeFileKey(file)
  const saved = findSession(fileKey)
  if (!saved) {
    // localStorage 也没有，走全新上传
    return startUpload(file, userId)
  }
  // 从 localStorage 恢复，跳过 init
  return startUpload(file, userId, {
    uploadId: saved.uploadId,
    totalChunks: saved.totalChunks,
    completedChunks: uploadState.value.completedChunks, // 内存中还保留着
    fileName: saved.fileName,
    fileSize: saved.fileSize,
  })
}

/**
 * 并发上传所有未完成的分片
 */
async function uploadAllChunks(uploadId, file, totalChunks, completedSet, fileKey) {
  const pending = []
  for (let i = 0; i < totalChunks; i++) {
    if (!completedSet.has(i)) {
      pending.push(i)
    }
  }

  if (pending.length === 0) {
    return // 所有分片已完成
  }

  const executing = new Set()
  for (const chunkIndex of pending) {
    if (abortController.signal.aborted) break

    const task = uploadOneChunk(uploadId, file, chunkIndex, totalChunks, fileKey)
    executing.add(task)
    task.finally(() => executing.delete(task))

    if (executing.size >= MAX_CONCURRENCY) {
      await Promise.race(executing)
    }
  }
  await Promise.all(executing)
}

/**
 * 上传单个分片（含重试）
 */
async function uploadOneChunk(uploadId, file, chunkIndex, totalChunks, fileKey) {
  const start = chunkIndex * CHUNK_SIZE
  const end = Math.min(start + CHUNK_SIZE, file.size)
  const blob = file.slice(start, end)

  let lastError = null
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    if (abortController.signal.aborted) return

    try {
      const formData = new FormData()
      formData.append('uploadId', uploadId)
      formData.append('chunkIndex', chunkIndex)
      formData.append('file', blob, `chunk_${chunkIndex}`)

      const resp = await chunkUpload(formData, abortController.signal)

      if (!resp.ok) {
        const errText = await resp.text()
        throw new Error(`分片 ${chunkIndex} 上传失败 (${resp.status}): ${errText}`)
      }

      const newSet = new Set(uploadState.value.completedChunks)
      newSet.add(chunkIndex)
      uploadState.value.completedChunks = newSet
      uploadState.value.uploadedBytes = newSet.size * CHUNK_SIZE
      uploadState.value.progress = Math.round((newSet.size / totalChunks) * 100)
      updateSpeed(blob.size)

      if (fileKey) {
        saveSession(fileKey, { uploadId, fileName: file.name, fileSize: file.size, totalChunks })
      }
      return
    } catch (err) {
      lastError = err
      if (attempt < MAX_RETRIES) {
        await sleep(1000 * Math.pow(2, attempt - 1))
      }
    }
  }
  throw lastError || new Error(`分片 ${chunkIndex} 上传失败`)
}

// ---- 取消 / 放弃 ----

function cancelUpload() {
  if (abortController) {
    abortController.abort()
  }
  uploadState.value.status = 'cancelled'
  // 不清 localStorage，不清 pendingFile，允许恢复
}

async function discardUpload() {
  const fileKey = currentFile ? computeFileKey(currentFile) : null
  if (uploadState.value.uploadId) {
    try {
      await chunkCancel({ uploadId: uploadState.value.uploadId })
    } catch { /* 忽略 */ }
  }
  if (fileKey) removeSession(fileKey)
  uploadState.value = { status: 'idle', completedChunks: new Set(), pendingFile: null }
  currentFile = null
}

// ---- 工具 ----

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// ---- 导出 ----

/**
 * 分片上传 + 断点续传 组合式函数（单例）。
 *
 * 两个续传场景：
 *  场景一：页面未刷新 → File 对象仍在内存 → 直接显示续传横幅，点击即可继续
 *  场景二：页面刷新后 → File 对象丢失 → 重新选择同一文件 → 自动识别并恢复
 */
export function useChunkedUpload() {
  return {
    uploadState,
    startUpload,
    resumeWithoutRecheck,
    cancelUpload,
    discardUpload,
    matchUpload,
  }
}
