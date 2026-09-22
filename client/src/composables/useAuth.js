import { ref } from 'vue'
import { login as apiLogin, register as apiRegister } from '../api/index.js'
import { useNotice } from './useNotice.js'

// ---- 模块级状态（单例） ----
const currentUser = ref(null)
const showAuthModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ username: '', password: '', nickname: '' })

const { showMsg } = useNotice()

function openAuthModal() {
  showAuthModal.value = true
  authMessage.value = ''
  authForm.value = { username: '', password: '', nickname: '' }
}

function closeAuthModal() {
  showAuthModal.value = false
}

function switchAuthMode() {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  authMessage.value = ''
}

async function handleAuth() {
  if (!authForm.value.username || !authForm.value.password) {
    authMessage.value = '请输入完整的账号和密码'
    authError.value = true
    return
  }
  authLoading.value = true
  authMessage.value = ''

  // 用局部变量捕获当前模式，避免 await 期间 authMode 被切换导致逻辑错乱
  const isLogin = authMode.value === 'login'
  try {
    const res = isLogin ? await apiLogin(authForm.value) : await apiRegister(authForm.value)
    const data = await res.json()
    if (data.code === 200) {
      if (isLogin) {
        currentUser.value = data.userInfo
        localStorage.setItem('user', JSON.stringify(data.userInfo))
        closeAuthModal()
        showMsg(`欢迎回来，${data.userInfo.nickname}`)
        // 列表刷新由 useMedia 中 watch(currentUser) 统一驱动
      } else {
        authMessage.value = '注册成功，请直接登录'
        authError.value = false
        setTimeout(() => switchAuthMode(), 1000)
      }
    } else {
      authMessage.value = data.msg || '操作失败'
      authError.value = true
    }
  } catch (e) {
    console.error(e)
    authMessage.value = '网络连接错误'
    authError.value = true
  } finally {
    authLoading.value = false
  }
}

function logout() {
  currentUser.value = null
  localStorage.removeItem('user')
  showMsg('已退出系统')
  // 列表清空由 useMedia 中 watch(currentUser) 统一驱动
}

// 启动时从 localStorage 恢复登录态
function restoreSession() {
  const savedUser = localStorage.getItem('user')
  if (savedUser) {
    try {
      currentUser.value = JSON.parse(savedUser)
    } catch (e) { /* 忽略损坏的缓存 */ }
  }
}

export function useAuth() {
  return {
    currentUser,
    showAuthModal,
    authMode,
    authLoading,
    authMessage,
    authError,
    authForm,
    openAuthModal,
    closeAuthModal,
    switchAuthMode,
    handleAuth,
    logout,
    restoreSession,
  }
}
