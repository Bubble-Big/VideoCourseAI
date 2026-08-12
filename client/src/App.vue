<template>
  <div class="app-stage">
    <div class="ambient-noise"></div>
    <div class="ambient-glow"></div>

    <AppNavbar />

    <main class="main-container">
      <UploadZone />
      <VideoList />
      <ResultSidebar />
      <AuthModal />
    </main>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import AppNavbar from './components/AppNavbar.vue'
import UploadZone from './components/UploadZone.vue'
import VideoList from './components/VideoList.vue'
import ResultSidebar from './components/ResultSidebar.vue'
import AuthModal from './components/AuthModal.vue'
import { useBootstrap } from './composables/useBootstrap.js'

const { start, stop } = useBootstrap()

onMounted(start)
onUnmounted(stop)
</script>
