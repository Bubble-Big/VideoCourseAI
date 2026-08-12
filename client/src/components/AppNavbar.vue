<template>
  <header class="navbar">
    <div class="nav-content">
      <div class="brand">
        <span class="brand-videocourse">VideoCourse</span>
        <span class="brand-ai">AI</span>
        <span class="beta-badge">PRO</span>
      </div>

      <div class="nav-controls">
        <button v-if="!currentUser" class="auth-btn" @click="openAuthModal">
          <span class="btn-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
          </span>
          登录 / 注册
        </button>

        <div v-else class="user-profile">
          <span class="user-name">:: {{ currentUser.nickname }} ::</span>
          <button class="logout-btn" @click="logout" title="退出登录">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
          </button>
        </div>

        <div class="status-pill" :class="{ 'is-active': uploading }">
          <div class="status-dot"></div>
          <span class="status-text">{{ uploading ? '数据传输中...' : '系统就绪' }}</span>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { useAuth } from '../composables/useAuth.js'
import { useUpload } from '../composables/useUpload.js'

const { currentUser, openAuthModal, logout } = useAuth()
const { uploading } = useUpload()
</script>
