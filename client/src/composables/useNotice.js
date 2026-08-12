import { ref } from 'vue'

// 全局通知条状态（模块级 → 单例）
const message = ref('')

// 显示通知，4 秒后自动清除（仅当内容仍是同一条时才清除，避免误删后续消息）
function showMsg(msg, isError = false) {
  message.value = msg
  setTimeout(() => { if (message.value === msg) message.value = '' }, 4000)
}

export function useNotice() {
  return { message, showMsg }
}
