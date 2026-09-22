<template>
  <div v-if="showAuthModal" class="auth-backdrop">
    <div class="auth-panel">
      <div class="auth-header">
        <h2 class="auth-title">{{ authMode === 'login' ? '用户登录' : '新用户注册' }}</h2>
        <button class="close-btn" @click="closeAuthModal">×</button>
      </div>
      <div class="auth-body">
        <div class="input-group">
          <label>USERNAME</label>
          <input v-model="authForm.username" type="text" placeholder="输入账号" />
        </div>
        <div class="input-group">
          <label>PASSWORD</label>
          <input v-model="authForm.password" type="password" placeholder="输入密码" />
        </div>
        <div class="input-group" v-if="authMode === 'register'">
          <label>NICKNAME (昵称)</label>
          <input v-model="authForm.nickname" type="text" placeholder="设置一个好听的名字" />
        </div>
        <div class="auth-action">
          <button class="cyber-btn" @click="handleAuth" :disabled="authLoading">
            <span v-if="!authLoading">{{ authMode === 'login' ? '立即登录' : '提交注册' }}</span>
            <span v-else>请求处理中...</span>
          </button>
        </div>
        <div class="auth-toggle">
          <span class="toggle-text">{{ authMode === 'login' ? '没有账号?' : '已有账号?' }}</span>
          <button class="toggle-link" @click="switchAuthMode">{{ authMode === 'login' ? '去注册' : '去登录' }}</button>
        </div>
        <p v-if="authMessage" class="auth-msg" :class="{'error': authError}">{{ authMessage }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useAuth } from '../composables/useAuth.js'

const { showAuthModal, authMode, authForm, authLoading, authMessage, authError, handleAuth, switchAuthMode, closeAuthModal } = useAuth()
</script>
