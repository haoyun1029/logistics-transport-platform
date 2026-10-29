<script setup lang="ts">
import { ref } from 'vue'
import { clearSession, portalForRole, saveToken } from '../auth'
import type { Account } from '../auth'

const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const showPassword = ref(false)
const expired = new URLSearchParams(window.location.search).has('expired')
const passwordChanged = new URLSearchParams(window.location.search).has('passwordChanged')
const recovery = ref(false)

async function login() {
  if (busy.value) return
  error.value = ''
  busy.value = true
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value.trim(), password: password.value }),
    })
    const payload = await response.json().catch(() => null) as { token?: string; user?: Account; message?: string } | null
    if (!response.ok) throw new Error(payload?.message ?? '登录失败，请稍后重试。')
    if (!payload?.token || !payload.user) throw new Error('登录响应不完整，请稍后重试。')
    const portal = portalForRole(payload.user.role)
    clearSession()
    saveToken(payload.token)
    password.value = ''
    window.location.replace(payload.user.passwordChangeRequired ? '/change-password' : `/${portal}`)
  } catch (failure) {
    error.value = failure instanceof TypeError ? '无法连接服务器，请检查后端是否已启动。' : failure instanceof Error ? failure.message : '登录失败。'
    busy.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-story" aria-label="运筹物流平台">
      <div class="brand"><span class="brand-mark">⌁</span><span><strong>运筹</strong><small>LOGISTICS OS</small></span></div>
      <div class="login-intro"><p class="eyebrow">TRANSPORT · CONNECT · DELIVER</p><h1>每一次运输，<br>都有清晰的方向。</h1><p class="login-description">连接任务、车辆与司机，<br>让调度有序，让运输可追溯。</p>
        <div class="login-network" aria-hidden="true"><div class="network-route"><span>任务创建</span><i></i><span>调度分配</span><i></i><span>运输执行</span><i></i><span>送达反馈</span></div><div class="network-caption"><span>01 — 04</span><span>全流程运输管理</span></div></div>
      </div>
      <p class="login-story-footer">物流运输任务调度与路径优化系统</p>
    </section>
    <section class="login-panel">
      <div class="login-card"><p class="eyebrow">WELCOME BACK</p><h2>账号登录</h2><p class="login-help">使用你的账号登录，进入对应业务端。</p>
        <p v-if="expired && !error" class="alert info" role="status">登录已过期，请重新登录。</p>
        <p v-if="passwordChanged && !error" class="alert success" role="status">密码修改成功，原有登录已退出，请使用新密码登录。</p>
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>
        <form class="signin-form" @submit.prevent="login">
          <label for="login-username">用户名</label><input id="login-username" v-model="username" name="username" autocomplete="username" placeholder="请输入用户名" required :disabled="busy" autofocus>
          <label for="login-password">密码</label><div class="password-field"><input id="login-password" v-model="password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" required :disabled="busy"><button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button></div>
          <button class="signin-submit" type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}<span aria-hidden="true">→</span></button>
        </form>
        <p class="registration-link"><button type="button" class="outline-button" :aria-expanded="recovery" @click="recovery = !recovery">忘记密码？</button></p>
        <div v-if="recovery" class="alert info" role="status">司机忘记密码时，请联系本平台超级管理员，提供用户名并配合核实身份。管理员重置后会向你提供临时密码；登录后须先设置新密码，才能进入司机端。审核未通过或已停用的账号仍不能登录。</div>
        <p class="registration-link">还没有司机账号？<a href="/register">司机注册</a></p>
        <div class="login-destinations"><strong>一个账号对应一个业务端</strong><p>管理员端 · 调度员端 · 司机端</p><small>系统根据账号权限自动进入对应页面。司机账号需先通过管理员审核。</small></div>
      </div>
      <p class="login-copyright">运筹 · LOGISTICS OS</p>
    </section>
  </main>
</template>
