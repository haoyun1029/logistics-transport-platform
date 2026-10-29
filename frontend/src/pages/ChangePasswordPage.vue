<script setup lang="ts">
import { ref } from 'vue'
import type { Account } from '../auth'
import { signOut } from '../auth'
import PasswordChangeForm from '../components/PasswordChangeForm.vue'
defineProps<{ account: Account }>()
const error = ref('')
async function logout() { try { await signOut() } catch (e) { error.value = e instanceof Error ? e.message : '退出失败。' } }
</script>
<template>
  <main class="session-screen">
    <section class="session-card password-change-card">
      <p class="kicker">ACCOUNT SECURITY</p><h1>请先修改临时密码</h1>
      <p>账号：{{ account.username }}。设置新密码后即可进入业务端。</p>
      <PasswordChangeForm temporary />
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <button class="outline-button" type="button" @click="logout">退出登录</button>
    </section>
  </main>
</template>
<style scoped>
.password-change-card { width: min(100%, 520px); text-align: left; }
.password-change-card > button { margin-top: 1rem; }
</style>
