<template>
  <div class="sidebar-backdrop" v-if="sidebar.visible" @click="closeSidebar"></div>
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
      <div v-if="sidebar.loading" class="loading-state"><div class="quantum-loader small"></div><p>数据流处理中...</p></div>
      <div v-else>
        <div v-if="sidebar.type === 'ai'" class="markdown-content" v-html="renderedMarkdown"></div>
        <div v-else class="text-content"><pre>{{ sidebar.content }}</pre></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useMedia } from '../composables/useMedia.js'

const { sidebar, renderedMarkdown, closeSidebar } = useMedia()
</script>
