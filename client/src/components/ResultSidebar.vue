<template>
  <div class="sidebar-panel" :class="{ 'is-open': sidebar.visible }">
    <div class="sidebar-header">
      <div class="sidebar-title">
        <span class="icon" v-if="sidebar.type === 'ai'">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="M20.2 6.47l-1.4 1.4"></path><path d="M15.9 5.35l-1.4-1.4"></path><path d="M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0"></path></svg>
        </span>
        <span class="icon" v-else>
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
        </span>
        {{ sidebar.title }}
      </div>
      <button class="close-btn" @click="closeSidebar">×</button>
    </div>
    <div class="sidebar-body">
      <div v-if="sidebar.loading" class="loading-state"><div class="quantum-loader small"></div><p>{{ sidebar.content ? sidebar.content : '数据流处理中...' }}</p></div>
      <div v-else>
        <div v-if="sidebar.type === 'ai'" class="markdown-content" v-html="renderedMarkdown"></div>
        <div v-else class="text-content"><pre>{{ sidebar.content }}</pre></div>

        <!-- 重新生成按钮 -->
        <div v-if="showRegenerateButton" class="regenerate-section">
          <button
            class="regenerate-btn"
            @click="handleRegenerate"
            :disabled="regenerating"
          >
            <span v-if="regenerating" class="btn-loading">
              <div class="quantum-loader tiny"></div>
              重新生成中...
            </span>
            <span v-else>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21.5 2v6h-6M2.5 22v-6h6M2 11.5a10 10 0 0 1 18.8-4.3M22 12.5a10 10 0 0 1-18.8 4.2"/>
              </svg>
              重新生成
            </span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useMedia } from '../composables/useMedia.js'
import { useConfirm } from '../composables/useConfirm.js'

const { sidebar, renderedMarkdown, closeSidebar, refreshMediaList, aiAnalyze, transcribe } = useMedia()
const { confirm: showConfirm } = useConfirm()

const regenerating = ref(false)

// 显示重新生成按钮的条件
// AI 分析：SUCCESS 或 FAILED 状态时显示
// 文字提取：仅 FAILED 状态时显示
const showRegenerateButton = computed(() => {
  if (!sidebar.value.state || sidebar.value.loading) return false

  if (sidebar.value.type === 'ai') {
    return sidebar.value.state === 'SUCCESS' || sidebar.value.state === 'FAILED'
  } else if (sidebar.value.type === 'text') {
    return sidebar.value.state === 'FAILED'
  }
  return false
})

// 处理重新生成
async function handleRegenerate() {
  if (regenerating.value) return

  // 确认对话框
  const confirmMessage = sidebar.value.type === 'ai'
    ? '重新生成将消耗 AI 配额，确定继续吗？'
    : '确定要重新提取文字吗？'

  const confirmed = await showConfirm(confirmMessage, '确认操作')
  if (!confirmed) return

  regenerating.value = true

  try {
    // 调用 API（传递 force=true）
    // aiAnalyze/transcribe 内部会自动：
    // 1. 打开侧边栏并设置 loading 状态
    // 2. 调用后端 API
    // 3. 启动 SSE 订阅
    // 4. SSE 推送会自动更新侧边栏
    if (sidebar.value.type === 'ai') {
      await aiAnalyze(sidebar.value.id, true)
    } else {
      await transcribe(sidebar.value.id, true)
    }
  } catch (error) {
    console.error('重新生成失败:', error)
  } finally {
    regenerating.value = false
  }
}
</script>

<style scoped>
.regenerate-section {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}

.regenerate-btn {
  width: 100%;
  padding: 10px 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.regenerate-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.regenerate-btn:active:not(:disabled) {
  transform: translateY(0);
}

.regenerate-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-loading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.quantum-loader.tiny {
  width: 16px;
  height: 16px;
  border-width: 2px;
}
</style>
