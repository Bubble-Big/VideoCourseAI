<template>
  <teleport to="body">
    <div v-if="dialog.visible" class="confirm-backdrop" @click="handleCancel">
      <div class="confirm-panel" @click.stop>
        <div class="confirm-header">
          <div class="confirm-icon">⚠</div>
        </div>
        <div class="confirm-body">
          <h3 class="confirm-title">{{ dialog.title }}</h3>
          <p class="confirm-message">{{ dialog.message }}</p>
        </div>
        <div class="confirm-footer">
          <button class="confirm-btn cancel" @click="handleCancel">{{ dialog.cancelText }}</button>
          <button class="confirm-btn confirm" @click="handleConfirm">{{ dialog.confirmText }}</button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { useConfirm } from '../composables/useConfirm.js'

const { dialog, handleConfirm, handleCancel } = useConfirm()
</script>
