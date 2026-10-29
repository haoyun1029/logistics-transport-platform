<script setup lang="ts">
import { ref } from 'vue'
import { clearSession, sessionFetch } from '../auth'
defineProps<{ temporary?: boolean }>()
const currentPassword = ref(''), newPassword = ref(''), confirmation = ref('')
const busy = ref(false), error = ref('')
async function change() {
  if (busy.value) return
  error.value = ''
  if (newPassword.value !== confirmation.value) { error.value = '两次输入的新密码不一致。'; return }
  if (newPassword.value === currentPassword.value) { error.value = '新密码不能与当前密码相同。'; return }
  busy.value = true
  try {
    const response = await sessionFetch('/api/auth/change-password', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword: currentPassword.value, newPassword: newPassword.value }),
    })
    if (!response.ok) {
      const data = await response.json().catch(() => null)
      throw new Error(data?.message ?? '密码修改失败，请重试。')
    }
    currentPassword.value = ''; newPassword.value = ''; confirmation.value = ''
    clearSession()
    window.location.replace('/login?passwordChanged=1')
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '密码修改失败。' }
  finally { busy.value = false }
}
</script>
<template>
  <form class="business-form" @submit.prevent="change">
    <p class="source-note">{{ temporary ? '输入管理员提供的临时密码，再设置你自己的新密码。' : '修改后，所有设备的原有登录会退出，请使用新密码重新登录。' }}</p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <label>{{ temporary ? '临时密码' : '当前密码' }}<input v-model="currentPassword" type="password" autocomplete="current-password" required maxlength="128" :disabled="busy"></label>
    <label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" required minlength="10" maxlength="128" placeholder="10–128 个字符" :disabled="busy"></label>
    <label>确认新密码<input v-model="confirmation" type="password" autocomplete="new-password" required minlength="10" maxlength="128" :disabled="busy"></label>
    <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '正在修改…' : '保存新密码并重新登录' }}</button>
  </form>
</template>
