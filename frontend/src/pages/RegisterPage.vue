<script setup lang="ts">
import { ref } from 'vue'

const username = ref('')
const displayName = ref('')
const phone = ref('')
const password = ref('')
const confirmation = ref('')
const busy = ref(false)
const submitted = ref(false)
const error = ref('')

async function register() {
  if (busy.value || submitted.value) return
  error.value = ''
  if (password.value !== confirmation.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    const response = await fetch('/api/auth/register', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value.trim(), displayName: displayName.value.trim(), phone: phone.value.trim(), password: password.value }),
    })
    const result = await response.json().catch(() => null)
    if (!response.ok) throw new Error(result?.message ?? '注册失败，请稍后重试。')
    password.value = ''; confirmation.value = ''
    submitted.value = true
  } catch (failure) {
    error.value = failure instanceof TypeError ? '无法连接服务器，请稍后重试。' : failure instanceof Error ? failure.message : '注册失败。'
  } finally { busy.value = false }
}
</script>

<template>
  <main class="registration-page">
    <a class="registration-brand" href="/login">⌁ 运筹 <small>LOGISTICS OS</small></a>
    <section class="registration-card">
      <p class="eyebrow">DRIVER REGISTRATION</p><h1>司机注册</h1>
      <p class="login-help">提交资料后，由管理员审核。审核通过即可登录司机端。</p>
      <div v-if="submitted" class="registration-success" role="status"><span>✓</span><h2>注册申请已提交</h2><p>账号 <strong>{{ username.trim().toLowerCase() }}</strong> 正在等待审核。请保留你的用户名和密码，审核通过后再登录。</p><a class="primary-button" href="/login">返回登录</a></div>
      <form v-else class="signin-form registration-form" @submit.prevent="register">
        <p v-if="error" class="alert error" role="alert">{{ error }}</p>
        <label for="register-username">用户名</label><input id="register-username" v-model="username" autocomplete="username" required pattern="[A-Za-z0-9_.\-]{3,32}" minlength="3" maxlength="32" placeholder="3–32 位字母、数字、点、下划线或连字符" :disabled="busy">
        <label for="register-name">姓名</label><input id="register-name" v-model="displayName" autocomplete="name" required maxlength="80" placeholder="请输入姓名" :disabled="busy">
        <label for="register-phone">联系方式（选填）</label><input id="register-phone" v-model="phone" autocomplete="tel" type="tel" maxlength="32" placeholder="手机号或其他联系方式" :disabled="busy">
        <label for="register-password">密码</label><input id="register-password" v-model="password" type="password" autocomplete="new-password" required minlength="10" maxlength="128" placeholder="10–128 个字符" :disabled="busy">
        <label for="register-confirmation">确认密码</label><input id="register-confirmation" v-model="confirmation" type="password" autocomplete="new-password" required minlength="10" maxlength="128" placeholder="再次输入密码" :disabled="busy">
        <button class="signin-submit" :disabled="busy" type="submit">{{ busy ? '正在提交…' : '提交注册申请' }}<span aria-hidden="true">→</span></button>
      </form>
      <p v-if="!submitted" class="registration-link">已有账号？<a href="/login">返回登录</a></p>
    </section>
  </main>
</template>
