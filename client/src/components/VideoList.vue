<template>
  <section v-if="list.length > 0" class="workspace-section">
    <div class="section-header"><h3>工作台</h3><div class="count-chip">{{ list.length }} TASKS</div></div>
    <div class="video-list">
      <div v-for="item in list" :key="item.id" class="video-row">

        <div class="row-left">
          <div class="meta-icon">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
          </div>
          <div class="row-filename" :title="item.filename">{{ item.filename }}</div>
          <div class="row-meta">
            <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
            <span v-if="item.fileSize" class="size-tag">{{ formatSize(item.fileSize) }}</span>
            <span class="status-indicator" :class="item.status.toLowerCase()">
              {{ item.status === 'COMPLETED' ? 'READY' : 'PROCESSING' }}
            </span>
          </div>
        </div>

        <div class="row-actions">
          <button class="row-btn" @click="downloadAudio(item)" title="下载音频">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
            <span>下载音频</span>
          </button>

          <button
              class="row-btn"
              :disabled="item.status !== 'COMPLETED'"
              @click="transcribe(item.id)"
              title="提取文字"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
            <span>提取文字</span>
          </button>

          <button
              class="row-btn ai-btn"
              :disabled="item.status !== 'COMPLETED'"
              @click="aiAnalyze(item.id)"
              title="AI 智能总结"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg>
            <span>AI 总结</span>
          </button>

          <button class="row-btn delete" @click.stop="deleteItem(item)" title="删除">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { useMedia } from '../composables/useMedia.js'
import { formatSize, formatTime } from '../utils/format.js'

const { list, downloadAudio, transcribe, aiAnalyze, deleteItem } = useMedia()
</script>
