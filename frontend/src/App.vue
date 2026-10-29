<script setup lang="ts">
import { defineAsyncComponent, onMounted, onUnmounted, ref } from 'vue'
import { clearSession, currentAccount, portalForRole, portalTitles, readToken } from './auth'
import type { Account, Portal } from './auth'
import LoginPage from './pages/LoginPage.vue'
import RegisterPage from './pages/RegisterPage.vue'
import ChangePasswordPage from './pages/ChangePasswordPage.vue'
import { resolvePage } from './navigation'

const WorkspaceLayout = defineAsyncComponent(() => import('./WorkspaceLayout.vue'))
const account = ref<Account | null>(null)
const portal = ref<Portal | null>(null)
const ready = ref(false)
const error = ref('')
const registering = ref(false)
const changingPassword = ref(false)

async function initialize() {
  ready.value = false
  error.value = ''
  const path = window.location.pathname.replace(/\/+$/, '') || '/'
  if (!readToken()) {
    clearSession()
    if (!['/login', '/register'].includes(path) || window.location.hash) {
      window.location.replace('/login')
      return
    }
    registering.value = path === '/register'
    document.title = `${registering.value ? '司机注册' : '登录'} · 运筹物流平台`
    ready.value = true
    return
  }
  try {
    // Every page load validates the session with the backend, including direct URL access.
    const user = await currentAccount()
    if (user.passwordChangeRequired) {
      if (path !== '/change-password') {
        window.location.replace('/change-password')
        return
      }
      changingPassword.value = true
      account.value = user
      portal.value = null
      document.title = '修改临时密码 · 运筹物流平台'
      ready.value = true
      return
    }
    changingPassword.value = false
    const target = portalForRole(user.role)
    if (!resolvePage(target, path)) {
      window.location.replace(`/${target}`)
      return
    }
    account.value = user
    portal.value = target
    document.title = `${portalTitles[target]} · 运筹物流平台`
    ready.value = true
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '无法连接服务器。'
  }
}

function returnToLogin() {
  clearSession()
  window.location.replace('/login')
}

function restoredPage(event: PageTransitionEvent) {
  if (event.persisted) initialize()
}
onMounted(() => {
  window.addEventListener('pageshow', restoredPage)
  initialize()
})
onUnmounted(() => window.removeEventListener('pageshow', restoredPage))
</script>

<template>
  <div v-if="!ready" class="session-screen" role="status">
    <div v-if="error" class="session-card"><h1>暂时无法进入业务端</h1><p role="alert">{{ error }}</p><button class="primary-button" @click="initialize">重新连接</button><button class="outline-button" @click="returnToLogin">返回登录</button></div>
    <p v-else>正在验证登录状态…</p>
  </div>
  <ChangePasswordPage v-else-if="changingPassword && account" :account="account" />
  <WorkspaceLayout v-else-if="portal && account" :portal="portal" :account="account" />
  <RegisterPage v-else-if="registering" />
  <LoginPage v-else />
</template>
