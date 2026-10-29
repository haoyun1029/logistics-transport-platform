<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { businessApi } from '../business'
import type { Profile } from '../business'
import PasswordChangeForm from '../components/PasswordChangeForm.vue'
const profile = ref<Profile | null>(null), name = ref(''), phone = ref(''), busy = ref(false), error = ref(''), notice = ref('')
onMounted(async () => { try { profile.value = await businessApi<Profile>('/profile'); name.value = profile.value.displayName; phone.value = profile.value.phone ?? '' } catch (e) { error.value = e instanceof Error ? e.message : '读取失败。' } })
async function save() { busy.value = true; error.value = ''; try { await businessApi('/profile', { displayName: name.value, phone: phone.value }); notice.value = '个人资料已保存，重新进入业务端后会更新侧栏姓名。' } catch (e) { error.value = e instanceof Error ? e.message : '保存失败。' } finally { busy.value = false } }
</script>
<template><div><section class="business-panel"><p class="kicker">MY PROFILE</p><h2>个人资料</h2><p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p><form v-if="profile" class="business-form" @submit.prevent="save"><p>账号：{{ profile.username }} · 角色由管理员授权。</p><label>姓名<input v-model="name" required maxlength="80" :disabled="busy"></label><label>联系方式<input v-model="phone" maxlength="32" :disabled="busy"></label><button class="primary-button" :disabled="busy" type="submit">保存个人资料</button></form></section><section class="business-panel"><h2>修改密码</h2><PasswordChangeForm /></section></div></template>
