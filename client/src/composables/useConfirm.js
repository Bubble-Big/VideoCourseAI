import { ref } from 'vue'

const dialog = ref({
  visible: false,
  title: '确认',
  message: '',
  confirmText: '确定',
  cancelText: '取消',
  resolve: null,
  reject: null
})

export function useConfirm() {
  function confirm(message, title = '确认', confirmText = '确定', cancelText = '取消') {
    return new Promise((resolve, reject) => {
      dialog.value = {
        visible: true,
        title,
        message,
        confirmText,
        cancelText,
        resolve,
        reject
      }
    })
  }

  function handleConfirm() {
    dialog.value.visible = false
    if (dialog.value.resolve) {
      dialog.value.resolve(true)
    }
  }

  function handleCancel() {
    dialog.value.visible = false
    if (dialog.value.resolve) {
      dialog.value.resolve(false)
    }
  }

  return {
    dialog,
    confirm,
    handleConfirm,
    handleCancel
  }
}
