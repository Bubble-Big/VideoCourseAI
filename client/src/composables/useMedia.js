import { ref, computed, watch } from 'vue'
import { marked } from 'marked'
import { useAuth } from './useAuth.js'
import { useNotice } from './useNotice.js'
import { useConfirm } from './useConfirm.js'
import * as api from '../api/index.js'
import { createTaskStreams } from './useTaskEvents.js'

// ---- 模块级状态（单例） ----
const list = ref([])
const sidebar = ref({
  visible: false,
  type: 'ai',
  id: null,
  title: '',
  content: '',
  loading: false,
  state: null,      // 任务状态：NONE/PENDING/PROCESSING/SUCCESS/FAILED
  mediaId: null     // 媒体文件 ID
})
const taskStreams = createTaskStreams()

const { currentUser } = useAuth()
const { showMsg } = useNotice()
const { confirm } = useConfirm()

// 列表随用户态联动：登录 → 刷新，登出 → 清空（避免与 useAuth 形成循环依赖）
watch(currentUser, (user) => {
  if (user) fetchList()
  else list.value = []
})

// Markdown 解析（剔除 DeepSeek 的 <think> 思考块）
const renderedMarkdown = computed(() => {
  if (!sidebar.value.content) return ''
  let cleanText = sidebar.value.content.replace(/<think>[\s\S]*?<\/think>/gi, "")
  if (cleanText.includes("</think>")) cleanText = cleanText.split("</think>").pop()
  if (!cleanText.trim()) cleanText = sidebar.value.content
  return marked.parse(cleanText)
})

async function fetchList() {
  try {
    if (currentUser.value) {
      const res = await api.getMediaList(currentUser.value.id)
      const data = await res.json()
      list.value = data
    } else {
      list.value = []
    }
  } catch (error) {
    console.error(error)
  }
}

async function deleteItem(item) {
  const confirmed = await confirm(
    `确认要永久删除 "${item.filename}" 吗？`,
    '删除确认',
    '确认删除',
    '取消'
  )
  if (!confirmed) return
  try {
    const res = await api.deleteMedia(item.id, currentUser.value ? currentUser.value.id : null)
    const text = await res.text()
    if (text === '删除成功') {
      showMsg('文件已销毁')
      list.value = list.value.filter(i => i.id !== item.id)
    } else {
      showMsg('❌ ' + text, true)
    }
  } catch (e) {
    showMsg('❌ 删除请求失败', true)
  }
}

async function downloadAudio(item) {
  let fileName = item.filename || 'audio.mp3'
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3"
  try {
    showMsg('正在转码并下载...')
    const res = await api.downloadAudio(item.id)
    if (!res.ok) throw new Error("Fail")
    const blob = await res.blob()
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    showMsg('✅ 下载完成')
  } catch (e) {
    showMsg('❌ 下载请求失败', true)
  }
}

async function transcribe(id, force = false) {
  const item = list.value.find(i => i.id === id)
  const st = item?.transcriptStatus || 'NONE'

  // force=true 时跳过状态检查，直接重新生成
  if (!force) {
    // 1. 已完成（成功/失败）→ 直接显示结果
    if (st === 'SUCCESS' || st === 'FAILED') {
      openSidebar('text', '全量文字提取', id)
      sidebar.value.content = st === 'FAILED' ? '❌ 提取失败，请稍后重试' : (item.transcriptText || '')
      sidebar.value.loading = false
      sidebar.value.state = st
      return
    }

    // 2. 正在处理 → 打开转圈，订阅 SSE
    if (st === 'PROCESSING') {
      openSidebar('text', '全量文字提取', id)
      sidebar.value.loading = true
      sidebar.value.content = "文字转写中..."
      sidebar.value.state = st
      startSSEStream(id, 'transcribe')
      return
    }
  }

  // 3. NONE 或 force=true → 提交请求
  openSidebar('text', '全量文字提取', id)
  sidebar.value.loading = true
  sidebar.value.content = "资源请求中..."
  sidebar.value.state = 'PENDING'
  try {
    const res = await api.transcribe(id, force)
    const data = await res.json()
    if (data.code !== 0) {
      showMsg(data.message || '提交失败', true)
      sidebar.value.content = data.message || '提交失败'
      sidebar.value.loading = false
      return
    }
    sidebar.value.content = "资源请求成功！准备接入转写..."
    startSSEStream(id, 'transcribe')
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

// AI 分析：含限流/锁错误的处理
async function aiAnalyze(id, force = false) {
  const item = list.value.find(i => i.id === id)
  const st = item?.aiStatus || 'NONE'

  // force=true 时跳过状态检查，直接重新生成
  if (!force) {
    // 1. 已完成（成功/失败）→ 直接显示结果
    if (st === 'SUCCESS' || st === 'FAILED') {
      openSidebar('ai', 'AI 智能总结', id)
      sidebar.value.content = st === 'FAILED' ? '❌ 分析失败，请稍后重试' : (item.aiSummary || '')
      sidebar.value.loading = false
      sidebar.value.state = st
      return
    }

    // 2. 正在处理 → 打开转圈，订阅 SSE
    if (st === 'PENDING' || st === 'PROCESSING') {
      openSidebar('ai', 'AI 智能总结', id)
      sidebar.value.loading = true
      sidebar.value.content = st === 'PENDING' ? 'AI调用中...' : 'AI分析中...'
      sidebar.value.state = st
      startSSEStream(id, 'ai')
      return
    }
  }

  // 3. 准备提交请求，打开侧边栏 loading
  openSidebar('ai', 'AI 智能总结', id)
  sidebar.value.loading = true
  sidebar.value.content = "资源请求中..."
  sidebar.value.state = 'PENDING'

  try {
    const res = await api.aiAnalyze(id, force)
    const data = await res.json()

    // 4. 检查后端返回：code 非 0 → 限流/锁/报错，任务被拒绝
    if (data.code !== 0) {
      showMsg(data.message || '提交失败', true)
      sidebar.value.content = data.message || '提交失败'
      sidebar.value.loading = false
      return
    }

    // 5. 成功投递，开始 SSE
    startSSEStream(id, 'ai')
    sidebar.value.content = "资源请求成功！准备接入AI..."
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

function startSSEStream(id, type) {
  const sseType = type === 'text' ? 'transcribe' : type  // 统一映射到后端 type 参数
  taskStreams.stop(id, sseType)  // 关闭同 key 的旧连接

  taskStreams.start(id, sseType, {
    userId: currentUser.value?.id,
    onEvent(event) {
      // 同步本地列表
      const item = list.value.find(i => i.id === event.mediaId)
      if (item) {
        if (sseType === 'ai') {
          item.aiStatus = event.state
          if (event.aiSummary) item.aiSummary = event.aiSummary
        } else {
          item.transcriptStatus = event.state
          if (event.transcriptText) item.transcriptText = event.transcriptText
        }
      }

      // 侧边栏不属于此任务时忽略 UI 更新
      if (!sidebar.value.visible || sidebar.value.id !== event.mediaId) return

      // 更新侧边栏状态
      sidebar.value.state = event.state

      if (event.state === 'SUCCESS') {
        sidebar.value.content = sseType === 'ai'
          ? (event.aiSummary || item?.aiSummary || '')
          : (event.transcriptText || item?.transcriptText || '')
        sidebar.value.loading = false
        showMsg('✅ 任务完成')
      } else if (event.state === 'FAILED') {
        sidebar.value.content = sseType === 'ai' ? '❌ 分析失败，请稍后重试' : '❌ 提取失败，请稍后重试'
        sidebar.value.loading = false
        showMsg('⚠️ 任务结束，但存在错误', true)
      } else if (event.state === 'PROCESSING') {
        sidebar.value.content = sseType === 'ai' ? 'AI分析中...' : '文字转写中...'
        sidebar.value.loading = true
      } else if (event.state === 'PENDING') {
        sidebar.value.content = 'AI调用中...'
        sidebar.value.loading = true
      }
    },
    onError(err) {
      console.warn('[SSE] 连接失败:', err)
      if (sidebar.value.visible && sidebar.value.id === id) {
        sidebar.value.content = '任务超时未完成，请稍后重试'
        sidebar.value.loading = false
      }
      showMsg('⚠️ 任务超时未完成', true)
    },
  })
}

function openSidebar(type, title, id) {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.id = id
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}

function closeSidebar() {
  // 关闭侧边栏时停止对应的 SSE 连接（若任务仍在进行）
  const id = sidebar.value.id
  const type = sidebar.value.type
  if (id != null) {
    const sseType = type === 'text' ? 'transcribe' : type
    taskStreams.stop(id, sseType)
  }
  sidebar.value.visible = false
}

export function useMedia() {
  return {
    list,
    sidebar,
    renderedMarkdown,
    fetchList,
    refreshMediaList: fetchList,  // 导出为 refreshMediaList 供 ResultSidebar 使用
    deleteItem,
    downloadAudio,
    transcribe,
    aiAnalyze,
    openSidebar,
    closeSidebar,
  }
}
