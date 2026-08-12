import { ref, computed, watch } from 'vue'
import { marked } from 'marked'
import { useAuth } from './useAuth.js'
import { useNotice } from './useNotice.js'
import * as api from '../api/index.js'

// ---- 模块级状态（单例） ----
const list = ref([])
const sidebar = ref({ visible: false, type: 'ai', title: '', content: '', loading: false })
const pollingTimers = ref({})

const { currentUser } = useAuth()
const { showMsg } = useNotice()

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
  if (!confirm(`确认要永久删除 "${item.filename}" 吗？`)) return
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
    alert("下载失败")
  }
}

async function transcribe(id) {
  const item = list.value.find(i => i.id === id)
  if (item && item.transcriptText) {
    openSidebar('text', '全量文字提取')
    sidebar.value.content = item.transcriptText
    sidebar.value.loading = false
    return
  }
  if (pollingTimers.value[id] && pollingTimers.value[id].type === 'text') {
    openSidebar('text', '全量文字提取')
    sidebar.value.loading = true
    sidebar.value.content = "📝 文字提取正在后台进行中..."
    return
  }
  openSidebar('text', '全量文字提取')
  sidebar.value.loading = true
  sidebar.value.content = "📝 提取任务已提交，正在识别语音流..."
  try {
    await api.transcribe(id)
    startPolling(id, 'text')
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

// AI 分析：含限流/锁错误的处理
async function aiAnalyze(id) {
  const item = list.value.find(i => i.id === id)

  // 1. 已有结果，直接显示
  if (item && item.aiSummary && !item.aiSummary.includes("任务已") && !item.aiSummary.includes("正在")) {
    openSidebar('ai', 'AI 智能总结')
    sidebar.value.content = item.aiSummary
    sidebar.value.loading = false
    return
  }

  // 2. 正在轮询，直接打开侧边栏
  if (pollingTimers.value[id] && pollingTimers.value[id].type === 'ai') {
    openSidebar('ai', 'AI 智能总结')
    sidebar.value.loading = true
    sidebar.value.content = "🚀 系统正在后台拼命计算中...\n\n(任务正在进行，无需重复提交)"
    return
  }

  // 3. 准备提交请求，打开侧边栏 loading
  openSidebar('ai', 'AI 智能总结')
  sidebar.value.loading = true
  sidebar.value.content = "🚀 正在向分布式集群请求计算资源..."

  try {
    const res = await api.aiAnalyze(id)
    const text = await res.text()

    // 4. 检查后端返回：限流/锁/报错 → 任务被拒绝
    if (text.includes("⚠️") || text.includes("❌")) {
      showMsg(text, true)
      sidebar.value.visible = false
      sidebar.value.loading = false
      return
    }

    // 5. 成功投递，开始轮询
    startPolling(id, 'ai')
    sidebar.value.content = text + "\n\n⏳ 等待消费者接单处理..."
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

function startPolling(id, type) {
  // 清理旧定时器
  if (pollingTimers.value[id]) clearInterval(pollingTimers.value[id].timer)
  console.log(`[轮询] 开始监听任务 ID: ${id}, 类型: ${type}`)

  const timer = setInterval(async () => {
    // 1. 强制刷新列表（带时间戳防缓存）
    await fetchList()
    const item = list.value.find(i => i.id === id)
    if (!item) return

    let isFinished = false
    let result = ''

    if (type === 'ai') {
      const text = item.aiSummary || ''
      // 纯文本判断：成功（含 Markdown 标题特征 "##"）或失败（含错误关键词）
      const isSuccess = text.includes("##")
      const isError = text.includes("失败") || text.includes("Error") || text.includes("超时") || text.includes("500")
      if (isSuccess || isError) {
        isFinished = true
        result = text
      }
    } else if (type === 'text') {
      const text = item.transcriptText || ''
      if (text && (text.length > 10 || text.includes("失败"))) {
        isFinished = true
        result = text
      }
    }

    // 2. 结算
    if (isFinished) {
      if (sidebar.value.visible && sidebar.value.title.includes(type === 'ai' ? 'AI' : '文字')) {
        sidebar.value.content = result
        sidebar.value.loading = false
      }

      if (result.includes("失败") || result.includes("Error")) {
        showMsg("⚠️ 任务结束，但存在错误", true)
      } else {
        showMsg("✅ 任务完成")
      }

      clearInterval(timer)
      delete pollingTimers.value[id]
    }
  }, 3000) // 3 秒轮询一次

  pollingTimers.value[id] = { timer, type }

  // 10 分钟强制兜底停止
  setTimeout(() => {
    if (pollingTimers.value[id]) {
      clearInterval(pollingTimers.value[id].timer)
      delete pollingTimers.value[id]
    }
  }, 600000)
}

function openSidebar(type, title) {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}

function closeSidebar() {
  sidebar.value.visible = false
}

export function useMedia() {
  return {
    list,
    sidebar,
    renderedMarkdown,
    fetchList,
    deleteItem,
    downloadAudio,
    transcribe,
    aiAnalyze,
    openSidebar,
    closeSidebar,
  }
}
