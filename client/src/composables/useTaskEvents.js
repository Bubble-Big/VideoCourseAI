// SSE 连接管理：指数退避重连、终态识别、页面可见性联动

import { getTaskEventsUrl } from '../api/index.js'

const TERMINAL_STATES = new Set(['SUCCESS', 'FAILED'])
const MAX_RETRIES = 10
const BASE_DELAY = 1000   // 1s 起步
const MAX_DELAY  = 15000  // 最大 15s

/**
 * 工厂函数：每次调用返回独立的连接池实例。
 * useMedia.js 在模块级创建一个单例即可。
 */
export function createTaskStreams() {
  // 连接池，key = "${id}:${type}"
  const pool = new Map()

  /**
   * 建立 SSE 连接并注册回调。
   * @param {number} id        mediaId
   * @param {string} type      'ai' | 'transcribe'
   * @param {object} callbacks { onEvent(event), onError(err) }
   */
  function start(id, type, { onEvent, onError } = {}) {
    const key = `${id}:${type}`
    pool.get(key)?.cleanup()  // 清理同 key 的旧连接

    let retryCount      = 0
    let es              = null
    let retryTimer      = null
    let visibilityHandler = null
    let active          = true

    const url = getTaskEventsUrl(id, type)

    function connect() {
      if (!active) return
      es = new EventSource(url)

      es.onmessage = (e) => {
        let event
        try { event = JSON.parse(e.data) } catch { return }
        onEvent?.(event)
        // 终态：让服务端关闭连接后，客户端也主动清理
        if (TERMINAL_STATES.has(event.state)) cleanup()
      }

      es.onerror = () => {
        // readyState CLOSED(2) 表示服务端返回非 2xx（4xx/5xx 首次），不再重连
        const isFinalError = es.readyState === EventSource.CLOSED
        es.close()
        es = null
        if (!active) return

        if (isFinalError) {
          onError?.(new Error('服务端拒绝连接（4xx），停止重连'))
          cleanup()
          return
        }

        if (retryCount >= MAX_RETRIES) {
          onError?.(new Error(`SSE 已重连 ${MAX_RETRIES} 次，放弃`))
          cleanup()
          return
        }

        const delay = Math.min(BASE_DELAY * (2 ** retryCount), MAX_DELAY)
        retryCount++

        // 页面隐藏时暂停重连，等页面重新可见
        if (document.visibilityState === 'hidden') {
          visibilityHandler = () => {
            visibilityHandler = null
            retryTimer = setTimeout(connect, delay)
          }
          document.addEventListener('visibilitychange', visibilityHandler, { once: true })
          return
        }

        retryTimer = setTimeout(connect, delay)
      }
    }

    function cleanup() {
      active = false
      if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
      if (visibilityHandler) {
        document.removeEventListener('visibilitychange', visibilityHandler)
        visibilityHandler = null
      }
      if (es) { es.close(); es = null }
      pool.delete(key)
    }

    pool.set(key, { cleanup })
    connect()
  }

  function stop(id, type) {
    pool.get(`${id}:${type}`)?.cleanup()
  }

  function stopAll() {
    pool.forEach(c => c.cleanup())
    pool.clear()
  }

  return { start, stop, stopAll }
}
