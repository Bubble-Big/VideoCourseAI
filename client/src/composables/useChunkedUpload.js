import { ref, computed } from 'vue'

const CHUNK_SIZE = 5 * 1024 * 1024        // 5MB 每片
const MAX_CONCURRENCY = 3                  // 最大并发数
const MAX_RETRIES = 3                      // 每片最多重试次数
const BASE_URL = 'http://localhost:9090'

const LS_KEY = 'chunked_upload'            // localStorage 键名

/**
 * 分片上传 + 断点续传 组合式函数
 *
 * 用法：
 *   const { uploadState, startUpload, cancelUpload } = useChunkedUpload()
 *   startUpload(file, userId)    // 开始上传
 */
export function useChunkedUpload() {
  // ---- 响应式状态 ----
  const uploadState = ref({
    status: 'idle',          // idle | hashing | uploading | merging | done | error | cancelled
    uploadId: null,
    fileName: '',
    fileSize: 0,
    totalChunks: 0,
    completedChunks: new Set(),
    progress: 0,             // 0 ~ 100
    uploadedBytes: 0,
    speed: 0,                // 字节/秒
    eta: null,               // 预计剩余秒数
    error: null,
    mediaId: null,
    filePath: null,
    duplicateMediaId: null,  // 去重提示
  })

  // 内部非响应式变量
  let abortController = null
  let speedSamples = []       // 速度采样窗口 [{time, bytes}]
  let startTime = 0

  // ---- 持久化 ----

  function saveToLocalStorage(uploadId, fileName, fileSize, totalChunks) {
    localStorage.setItem(LS_KEY, JSON.stringify({ uploadId, fileName, fileSize, totalChunks, timestamp: Date.now() }))
  }

  function loadFromLocalStorage() {
    try {
      const raw = localStorage.getItem(LS_KEY)
      if (!raw) return null
      const data = JSON.parse(raw)
      // 48 小时内有效
      if (Date.now() - data.timestamp > 48 * 3600 * 1000) {
        localStorage.removeItem(LS_KEY)
        return null
      }
      return data
    } catch {
      return null
    }
  }

  function clearLocalStorage() {
    localStorage.removeItem(LS_KEY)
  }

  // ---- 速度计算 ----

  function updateSpeed(chunkBytes) {
    const now = Date.now()
    speedSamples.push({ time: now, bytes: chunkBytes })
    // 只保留最近 5 秒
    speedSamples = speedSamples.filter(s => now - s.time < 5000)
    const totalBytes = speedSamples.reduce((sum, s) => sum + s.bytes, 0)
    const elapsed = (now - speedSamples[0].time) / 1000
    uploadState.value.speed = elapsed > 0 ? totalBytes / elapsed : 0
  }

  // ---- 主流程 ----

  /**
   * 入口：开始上传一个文件
   * @param {File} file  浏览器 File 对象
   * @param {number} userId
   */
  async function startUpload(file, userId) {
    if (!file) return

    // 重置状态
    abortController = new AbortController()
    speedSamples = []
    startTime = Date.now()

    const totalChunks = Math.ceil(file.size / CHUNK_SIZE)

    uploadState.value = {
      ...uploadState.value,
      status: 'hashing',
      fileName: file.name,
      fileSize: file.size,
      totalChunks,
      completedChunks: new Set(),
      progress: 0,
      uploadedBytes: 0,
      speed: 0,
      eta: null,
      error: null,
      duplicateMediaId: null,
    }

    try {
      // 1. 初始化上传
      const initResp = await fetch(`${BASE_URL}/media/api/chunk/init`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fileName: file.name,
          fileSize: file.size,
          totalChunks,
          userId
        }),
        signal: abortController.signal,
      })

      if (!initResp.ok) {
        throw new Error('初始化失败: ' + await initResp.text())
      }

      const initResult = await initResp.json()
      const initData = initResult.data  // 后端统一 Result<T> 包装

      if (initData.status === 'HINT_DUPLICATE') {
        uploadState.value.status = 'idle'
        uploadState.value.duplicateMediaId = initData.existingMediaId
        return // 让调用方处理提示
      }

      const uploadId = initData.uploadId
      const completedSet = new Set(initData.completedChunks || [])

      uploadState.value.uploadId = uploadId
      uploadState.value.completedChunks = completedSet
      uploadState.value.status = 'uploading'

      // 持久化
      saveToLocalStorage(uploadId, file.name, file.size, totalChunks)

      // 2. 上传所有分片
      await uploadAllChunks(uploadId, file, totalChunks, completedSet)

      // 3. 合并
      uploadState.value.status = 'merging'
      const mergeResp = await fetch(`${BASE_URL}/media/api/chunk/merge`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ uploadId, userId }),
      })

      if (mergeResp.status === 409) {
        throw new Error('合并正在进行中，请稍后刷新页面查看')
      }
      if (!mergeResp.ok) {
        throw new Error('合并失败: ' + await mergeResp.text())
      }

      const mergeResult = await mergeResp.json()
      const mergeData = mergeResult.data  // 后端统一 Result<T> 包装
      uploadState.value.status = 'done'
      uploadState.value.progress = 100
      uploadState.value.mediaId = mergeData.mediaId
      uploadState.value.filePath = mergeData.filePath

      clearLocalStorage()
    } catch (err) {
      if (err.name === 'AbortError') {
        uploadState.value.status = 'cancelled'
      } else {
        uploadState.value.status = 'error'
        uploadState.value.error = err.message
      }
    }
  }

  /**
   * 并发上传所有未完成的分片
   */
  async function uploadAllChunks(uploadId, file, totalChunks, completedSet) {
    // 构建待上传分片列表
    const pending = []
    for (let i = 0; i < totalChunks; i++) {
      if (!completedSet.has(i)) {
        pending.push(i)
      }
    }

    // 初始进度 = 已完成分片
    const completedBytes = completedSet.size * CHUNK_SIZE
    uploadState.value.uploadedBytes = completedBytes
    uploadState.value.progress = Math.round((completedSet.size / totalChunks) * 100)

    if (pending.length === 0) {
      return // 所有分片已完成（断点续传场景）
    }

    // 并发控制：最多 MAX_CONCURRENCY 个同时进行
    const executing = new Set()

    for (const chunkIndex of pending) {
      if (abortController.signal.aborted) break

      const task = uploadOneChunk(uploadId, file, chunkIndex, totalChunks)
      executing.add(task)
      task.finally(() => executing.delete(task))

      if (executing.size >= MAX_CONCURRENCY) {
        await Promise.race(executing)
      }
    }

    // 等待剩余任务
    await Promise.all(executing)
  }

  /**
   * 上传单个分片（含重试）
   */
  async function uploadOneChunk(uploadId, file, chunkIndex, totalChunks) {
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

        const resp = await fetch(`${BASE_URL}/media/api/chunk/upload`, {
          method: 'POST',
          body: formData,
          signal: abortController.signal,
        })

        if (!resp.ok) {
          const errText = await resp.text()
          throw new Error(`分片 ${chunkIndex} 上传失败 (${resp.status}): ${errText}`)
        }

        // 成功：更新进度
        const newSet = new Set(uploadState.value.completedChunks)
        newSet.add(chunkIndex)
        uploadState.value.completedChunks = newSet
        uploadState.value.uploadedBytes = newSet.size * CHUNK_SIZE
        uploadState.value.progress = Math.round((newSet.size / totalChunks) * 100)
        updateSpeed(blob.size)

        // 每完成一个分片就更新 localStorage（确保断点续传精度）
        saveToLocalStorage(uploadId, file.name, file.size, totalChunks)
        return
      } catch (err) {
        lastError = err
        if (attempt < MAX_RETRIES) {
          // 指数退避
          await sleep(1000 * Math.pow(2, attempt - 1))
        }
      }
    }

    throw lastError || new Error(`分片 ${chunkIndex} 上传失败`)
  }

  // ---- 查询状态（页面刷新恢复用） ----

  async function checkResumable() {
    const saved = loadFromLocalStorage()
    if (!saved) return null

    try {
      const resp = await fetch(`${BASE_URL}/media/api/chunk/check`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ uploadId: saved.uploadId }),
      })

      if (!resp.ok) return null
      const result = await resp.json()
      const data = result.data  // 后端统一 Result<T> 包装
      if (!data) return null

      if (data.status === 'UPLOADING') {
        // 恢复状态
        uploadState.value = {
          ...uploadState.value,
          status: 'idle', // 等待用户确认
          uploadId: saved.uploadId,
          fileName: saved.fileName,
          fileSize: saved.fileSize,
          totalChunks: saved.totalChunks,
          completedChunks: new Set(data.completedChunks || []),
          progress: data.totalChunks
            ? Math.round(((data.completedChunks || []).length / data.totalChunks) * 100)
            : 0,
        }
        return { uploadId: saved.uploadId, completedChunks: data.completedChunks || [], fileSize: saved.fileSize, fileName: saved.fileName, totalChunks: saved.totalChunks }
      }

      if (data.status === 'COMPLETED') {
        clearLocalStorage()
        return null // 已合并完成，无需恢复
      }

      // NOT_FOUND：清理过期记录
      clearLocalStorage()
      return null
    } catch {
      return null
    }
  }

  // ---- 取消 ----

  function cancelUpload() {
    if (abortController) {
      abortController.abort()
    }
    // 不清 localStorage，允许用户稍后恢复
    uploadState.value.status = 'cancelled'
  }

  // ---- 放弃（清理一切） ----

  async function discardUpload() {
    if (uploadState.value.uploadId) {
      try {
        await fetch(`${BASE_URL}/media/api/chunk/cancel`, {
          method: 'DELETE',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ uploadId: uploadState.value.uploadId }),
        })
      } catch { /* 忽略 */ }
    }
    clearLocalStorage()
    uploadState.value = { status: 'idle', completedChunks: new Set() }
  }

  // ---- 工具 ----

  function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms))
  }

  // ---- 导出 ----

  return {
    uploadState,
    startUpload,
    cancelUpload,
    discardUpload,
    checkResumable,
    clearLocalStorage,
  }
}
