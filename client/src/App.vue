<template>
  <div>
    <div class="app-stage" :class="{ 'sidebar-open': sidebar.visible }">
      <div class="ambient-noise"></div>
      <div class="ambient-glow"></div>

      <AppNavbar />

      <main class="main-container">
        <UploadZone />
        <VideoList />
        <AuthModal />
      </main>
    </div>

    <ResultSidebar />
    <ConfirmDialog />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import AppNavbar from './components/AppNavbar.vue'
import UploadZone from './components/UploadZone.vue'
import VideoList from './components/VideoList.vue'
import ResultSidebar from './components/ResultSidebar.vue'
import AuthModal from './components/AuthModal.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import { useBootstrap } from './composables/useBootstrap.js'
import { useMedia } from './composables/useMedia.js'

const { start, stop } = useBootstrap()
const { sidebar } = useMedia()

onMounted(start)
onUnmounted(stop)
</script>
