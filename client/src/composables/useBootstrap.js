import { useAuth } from './useAuth.js'
import { useUpload } from './useUpload.js'

// 应用启动/卸载编排：封装初始化顺序，App.vue 只做生命周期挂钩。
// 顺序依赖：restoreSession 恢复 currentUser 后，useMedia 的 watch 会自动触发 fetchList。
export function useBootstrap() {
  const auth = useAuth()
  const upload = useUpload()

  function start() {
    auth.restoreSession()          // 恢复登录态（若存在，列表随之自动加载）
    upload.installBeforeUnload()   // 注册离开页面提醒
  }

  function stop() {
    upload.uninstallBeforeUnload()
  }

  return { start, stop }
}
