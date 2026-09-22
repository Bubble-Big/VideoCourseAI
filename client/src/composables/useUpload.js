import { ref, watch } from 'vue'
import { useChunkedUpload } from './useChunkedUpload.js'
import { useAuth } from './useAuth.js'
import { useNotice } from './useNotice.js'
import { useMedia } from './useMedia.js'
import { useConfirm } from './useConfirm.js'
import * as api from '../api/index.js'

// ---- 模块级状态（单例） ----
const file = ref(null)
const videoUrl = ref('')
const uploading = ref(false)
const isDragOver = ref(false)
const resumeBanner = ref({ visible: false, fileName: '', progress: 0 })

const {
  uploadState: chunkState,
  startUpload,
  resumeWithoutRecheck,
  cancelUpload: cancelChunk,
  discardUpload,
  matchUpload,
} = useChunkedUpload()

const { currentUser, openAuthModal } = useAuth()
const { message, showMsg } = useNotice()
const { fetchList } = useMedia()
const { confirm } = useConfirm()

// 监听分片上传状态变化
watch(() => chunkState.value.status, (newStatus) => {
  if (newStatus === 'done') {
    uploading.value = false
    file.value = null
    resumeBanner.value.visible = false
    showMsg('✅ 分片上传完成')
    fetchList()
  } else if (newStatus === 'error') {
    uploading.value = false
    // 场景一：中断后显示续传横幅
    if (file.value && chunkState.value.fileName) {
      resumeBanner.value = {
        visible: true,
        fileName: chunkState.value.fileName,
        progress: chunkState.value.progress,
      }
    }
    showMsg('❌ ' + (chunkState.value.error || '上传失败'), true)
  } else if (newStatus === 'cancelled') {
    uploading.value = false
    // 取消也显示续传横幅（用户可能想稍后继续）
    if (file.value && chunkState.value.fileName) {
      resumeBanner.value = {
        visible: true,
        fileName: chunkState.value.fileName,
        progress: chunkState.value.progress,
      }
    }
    showMsg('⚠️ 上传已取消（可稍后恢复）')
  }
})

// HINT_DUPLICATE：去重提示弹框
watch(() => chunkState.value.duplicateMediaId, async (mediaId) => {
  if (mediaId) {
    uploading.value = false
    const fileName = chunkState.value.fileName
    const confirmed = await confirm(
      `同名视频文件「${fileName}」资料库中已存在，可能为重复文件，是否继续上传？`,
      '重复文件提醒',
      '坚持上传',
      '跳过'
    )
    if (confirmed) {
      await handleDuplicateForce()
    } else {
      await handleDuplicateSkip()
    }
  }
})

// ---- 去重操作 ----

async function handleDuplicateSkip() {
  file.value = null
  showMsg('已跳过重复文件')
}

async function handleDuplicateForce() {
  uploading.value = true
  message.value = '正在上传（已确认忽略重复提示）...'
  const userId = currentUser.value ? currentUser.value.id : null
  try {
    await startUpload(file.value, userId, null, true)
  } catch (error) {
    showMsg('❌ 上传失败: ' + error.message, true)
    uploading.value = false
  }
}

// ---- 续传横幅操作 ----

async function handleResumeContinue() {
  if (!file.value) return
  resumeBanner.value.visible = false
  uploading.value = true
  message.value = '正在恢复上传...'
  const userId = currentUser.value ? currentUser.value.id : null
  try {
    await resumeWithoutRecheck(file.value, userId)
  } catch (error) {
    showMsg('❌ 恢复上传失败: ' + error.message, true)
    uploading.value = false
  }
}

function handleResumeRestart() {
  resumeBanner.value.visible = false
  discardUpload()
  showMsg('已清除上传记录，请重新选择文件')
}

// ---- 文件选择 / 拖拽 ----

async function handleFileChange(e) {
  if (!currentUser.value) {
    e.target.value = ''
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  const selectedFile = e.target.files[0]
  if (!selectedFile) return
  file.value = selectedFile
  videoUrl.value = ''
  resumeBanner.value.visible = false
  await uploadFile()
}

async function handleDrop(e) {
  isDragOver.value = false
  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  const droppedFiles = e.dataTransfer.files
  if (!droppedFiles || droppedFiles.length === 0) return
  const selectedFile = droppedFiles[0]
  if (!selectedFile.type.startsWith('video/')) {
    showMsg('⚠️ 仅支持上传视频文件', true)
    return
  }
  file.value = selectedFile
  videoUrl.value = ''
  resumeBanner.value.visible = false
  await uploadFile()
}

// 文件上传 — 支持分片上传 + 断点续传
async function uploadFile() {
  if (!file.value) return
  uploading.value = true

  const userId = currentUser.value ? currentUser.value.id : null

  // 所有文件统一走分片上传（小于 5MB 的小文件只有一片）
  // 场景二：检查是否存在该文件的历史上传记录（文件重新选择后自动识别）
  message.value = '正在核对已上传分片...'
  const resumeInfo = await matchUpload(file.value)

  if (resumeInfo) {
    const confirmed = await confirm(
      `检测到该文件的未完成上传记录：\n文件：${resumeInfo.fileName}\n已完成：${resumeInfo.completedChunks.size}/${resumeInfo.totalChunks} 片\n\n是否继续上传？`,
      '续传确认',
      '继续上传',
      '重新开始'
    )
    if (confirmed) {
      try {
        await startUpload(file.value, userId, resumeInfo)
      } catch (error) {
        showMsg('❌ 恢复上传失败: ' + error.message, true)
        uploading.value = false
      }
      return
    }
    // 用户选择重新开始
  }

  // 全新上传
  message.value = '正在初始化分片上传...'
  try {
    await startUpload(file.value, userId)
  } catch (error) {
    showMsg('❌ 分片上传异常: ' + error.message, true)
    uploading.value = false
  }
}

// 链接上传
async function handleUrlUpload() {
  if (!videoUrl.value) return

  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }

  // 简单校验链接
  if (!videoUrl.value.startsWith('http')) {
    showMsg('⚠️ 请输入合法的 http/https 链接', true)
    return
  }

  uploading.value = true
  message.value = '正在解析链接并极速下载 (低码率模式)...'

  const formData = new FormData()
  formData.append('url', videoUrl.value)
  if (currentUser.value) formData.append('userId', currentUser.value.id)

  try {
    const res = await api.uploadUrl(formData)
    const text = await res.text()
    if (!res.ok) throw new Error(text)

    showMsg('✅ 链接资源已入库')
    videoUrl.value = ''
    fetchList()
  } catch (error) {
    console.error(error)
    let errMsg = error.message
    if (errMsg.includes("Unsupported URL")) errMsg = "不支持该平台链接"
    showMsg('❌ 解析失败: ' + errMsg, true)
  } finally {
    uploading.value = false
  }
}

// ---- beforeunload：页面关闭前提醒 ----

function beforeUnloadHandler(e) {
  if (chunkState.value.status === 'uploading' || chunkState.value.status === 'merging') {
    e.preventDefault()
    e.returnValue = '上传正在进行中，离开后需重新恢复'
    return e.returnValue
  }
}

function installBeforeUnload() {
  window.addEventListener('beforeunload', beforeUnloadHandler)
}

function uninstallBeforeUnload() {
  window.removeEventListener('beforeunload', beforeUnloadHandler)
}

export function useUpload() {
  return {
    file,
    videoUrl,
    uploading,
    isDragOver,
    resumeBanner,
    chunkState,
    handleFileChange,
    handleDrop,
    handleUrlUpload,
    handleResumeContinue,
    handleResumeRestart,
    cancelChunk,
    installBeforeUnload,
    uninstallBeforeUnload,
  }
}
