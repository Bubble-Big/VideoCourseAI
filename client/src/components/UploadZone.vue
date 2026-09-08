<template>
  <section class="hero-section">
    <h1 class="slogan-main">DECODE ALL VIDEOS</h1>
    <p class="slogan-sub">视频解构 · AI赋能</p>

    <div class="upload-wrapper">
      <input
          type="file"
          id="file-input"
          @change="handleFileChange"
          accept="video/*"
          hidden
      />

      <div
          class="upload-magnet"
          :class="{ 'processing': uploading, 'is-dragover': isDragOver }"
          @dragover.prevent="isDragOver = true"
          @dragleave.prevent="isDragOver = false"
          @drop.prevent="handleDrop"
      >
        <div class="split-container" v-if="!uploading">

          <label for="file-input" class="skew-pane pane-local">
            <div class="pane-content unskew">
              <div class="magnet-icon">
                <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
              </div>
              <span class="magnet-title">LOCAL FILE</span>
              <span class="magnet-desc">{{ isDragOver ? '松手上传' : '点击 / 拖拽本地文件' }}</span>
            </div>
          </label>

          <div class="split-gap"></div>

          <div class="skew-pane pane-url">
            <div class="pane-content unskew">
              <div class="magnet-icon">
                <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1 4-10z"></path></svg>
              </div>
              <span class="magnet-title">WEB LINK</span>
              <span class="magnet-desc">B站 / YouTube / 抖音</span>

              <div class="url-input-box" @click.stop>
                <input
                    v-model="videoUrl"
                    type="text"
                    placeholder="粘贴视频链接..."
                    @keyup.enter="handleUrlUpload"
                />
                <button class="url-go-btn" @click="handleUrlUpload">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
                </button>
              </div>
            </div>
          </div>

        </div>

        <div class="magnet-content busy" v-else>
          <!-- 分片上传进度 -->
          <template v-if="chunkState.status === 'uploading' || chunkState.status === 'merging' || chunkState.status === 'hashing'">
            <div class="quantum-loader"></div>
            <span class="busy-text">
              {{ chunkState.status === 'hashing' ? '正在扫描文件...' :
                 chunkState.status === 'merging' ? '正在合并分片...' :
                 `分片上传中 ${chunkState.progress}%` }}
            </span>
            <div class="progress-bar-wrap">
              <div class="progress-bar-fill" :style="{ width: chunkState.progress + '%' }"></div>
            </div>
            <div class="progress-detail">
              <span>{{ formatSize(chunkState.uploadedBytes) }} / {{ formatSize(chunkState.fileSize) }}</span>
              <span v-if="chunkState.speed > 0">{{ formatSize(chunkState.speed) }}/s</span>
            </div>
            <button class="cancel-upload-btn" @click="cancelChunk">取消上传</button>
          </template>
          <!-- 旧版整文件上传状态 -->
          <template v-else>
            <div class="quantum-loader"></div>
            <span class="busy-text">正在建立通道并解析资源...</span>
          </template>
        </div>

        <div class="border-glow"></div>
      </div>
    </div>
    <transition name="toast-pop">
      <div v-if="message" class="notification-bar" :class="{ 'error': message.startsWith('❌') || message.startsWith('⚠️') }">
        {{ message }}
      </div>
    </transition>

    <!-- 场景一：上传中断后 File 仍在内存，显示续传横幅 -->
    <transition name="toast-pop">
      <div v-if="resumeBanner.visible && !uploading" class="resume-banner">
        <div class="resume-info">
          <span class="resume-icon">⏸</span>
          <span>{{ resumeBanner.fileName }} 已完成 {{ resumeBanner.progress }}%，可继续未完成的上传</span>
        </div>
        <div class="resume-actions">
          <button class="resume-btn continue" @click="handleResumeContinue">继续上传</button>
          <button class="resume-btn restart" @click="handleResumeRestart">重新开始</button>
        </div>
      </div>
    </transition>
  </section>
</template>

<script setup>
import { useUpload } from '../composables/useUpload.js'
import { useNotice } from '../composables/useNotice.js'
import { formatSize } from '../utils/format.js'

const {
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
} = useUpload()

const { message } = useNotice()
</script>
