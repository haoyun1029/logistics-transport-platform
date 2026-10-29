<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { portalTitles, signOut } from './auth'
import type { Account, Portal } from './auth'
import { createWorkspace, workspaceKey } from './workspace'
import { navigation, normalizePath, resolvePage } from './navigation'

const props = defineProps<{ portal: Portal; account: Account }>()
const pageModules = import.meta.glob('./pages/*.vue')
const pages = Object.fromEntries(Object.entries(pageModules).map(([path, loader]) => [
  path.split('/').pop()!.replace('.vue', ''), defineAsyncComponent(loader as () => Promise<{ default: import('vue').Component }>),
]))
const workspace = createWorkspace(props.portal)
provide(workspaceKey, workspace)
const { loadBusiness, errorMessage, notice } = workspace
const leaving = ref(false)
const title = computed(() => portalTitles[props.portal])
const path = ref(normalizePath(window.location.pathname))
const currentPage = computed(() => resolvePage(props.portal, path.value) ?? navigation[props.portal][0]!)

// Only intercept ordinary clicks within the current portal. New-tab links keep browser behavior.
function navigate(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
  const link = (event.target as Element).closest<HTMLAnchorElement>('a[href]')
  if (!link || link.target || link.hasAttribute('download')) return
  const url = new URL(link.href)
  if (url.origin !== window.location.origin || !resolvePage(props.portal, url.pathname)) return
  event.preventDefault()
  const next = normalizePath(url.pathname)
  if (next === path.value) return
  window.history.pushState(null, '', next)
  path.value = next
  notice.value = ''
  window.scrollTo({ top: 0 })
}
function restorePath() {
  const next = normalizePath(window.location.pathname)
  if (!resolvePage(props.portal, next)) {
    window.location.replace(`/${props.portal}`)
    return
  }
  path.value = next
  notice.value = ''
}
watch(currentPage, page => { document.title = `${page.title} · ${title.value} · 运筹物流平台` }, { immediate: true })

async function logout() {
  leaving.value = true
  try { await signOut() }
  catch (failure) {
    errorMessage.value = failure instanceof Error ? failure.message : '退出失败。'
    leaving.value = false
  }
}
onMounted(() => {
  window.addEventListener('popstate', restorePath)
  loadBusiness()
})
onUnmounted(() => window.removeEventListener('popstate', restorePath))
</script>

<template>
  <div class="app-shell" :class="`workspace-${portal}`">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">⌁</span><span><strong>运筹</strong><small>LOGISTICS OS</small></span></div>
      <div class="workspace-label">{{ title }}</div>
      <nav class="side-nav" :aria-label="`${title}导航`" @click="navigate"><a v-for="item in navigation[portal]" :key="item.path" :href="item.path" :class="{ active: item.path === currentPage.path }" :aria-current="item.path === currentPage.path ? 'page' : undefined">{{ item.title }}</a></nav>
      <div class="side-note"><span class="pulse"></span>{{ title }}业务端</div>
      <div class="sidebar-footer"><span class="avatar">{{ account.displayName.slice(0, 1) }}</span><span><strong>{{ account.displayName }}</strong><small>{{ title }}</small></span></div>
    </aside>
    <main id="overview" class="main-content">
      <header class="topbar"><div class="breadcrumb">{{ title }} <span>/</span> {{ currentPage.title }}</div><div class="top-actions"><button class="outline-button" @click="loadBusiness">刷新数据</button><span class="login-user">{{ account.displayName }}</span><button class="outline-button" :disabled="leaving" @click="logout">{{ leaving ? '正在退出…' : '退出登录' }}</button></div></header>
      <div class="content-wrap" @click="navigate">
        <section class="page-heading"><div><p class="eyebrow">{{ portal === 'admin' ? 'ADMIN OPERATIONS' : portal === 'dispatcher' ? 'DISPATCH CONTROL' : 'DRIVER TASKS' }}</p><h1>{{ currentPage.title }}</h1><p>{{ currentPage.description }}</p></div><span class="env-pill"><i></i>{{ account.displayName }}，欢迎回来</span></section>
        <p v-if="errorMessage" class="alert error" role="alert">{{ errorMessage }}</p>
        <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
        <component :is="pages[currentPage.component]" :key="currentPage.path" :portal="portal" />
        <footer class="page-footer"><span>物流运输任务调度与路径优化系统</span><span>{{ title }}</span></footer>
      </div>
    </main>
  </div>
</template>
