<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { businessApi } from '../business'
import { currentAccount, sessionFetch } from '../auth'
import type { Account, Role } from '../auth'
type Person = { id: number; username: string; displayName: string; phone: string | null; role: Role; status: string; busy: boolean }
const account = ref<Account | null>(null)
const users = ref<Person[]>([])
const search = ref('')
const activeGroup = ref<'ADMIN' | 'DISPATCHER' | 'DRIVER'>('ADMIN')
const error = ref('')
const notice = ref('')
const busy = ref(false)
const creating = ref(false)
const editing = ref<Person | null>(null)
const name = ref(''), phone = ref(''), username = ref(''), password = ref('')
const newRole = ref('DISPATCHER')
const resetting = ref<Person | null>(null), identityVerified = ref(false), temporaryPassword = ref('')
const roles: Record<Role, string> = { SUPER_ADMIN: '超级管理员', ADMIN: '普通管理员', DISPATCHER: '调度员', DRIVER: '司机' }
const statuses: Record<string, string> = { APPROVED: '正常', PENDING: '待审核', REJECTED: '已拒绝', DISABLED: '已停用', DELETED: '已删除' }
const visible = computed(() => users.value.filter(u => `${u.username} ${u.displayName} ${u.phone || ''}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const userGroups = computed(() => [
  { role: 'ADMIN' as const, title: '管理员', hint: '负责系统配置、路线和基础资料维护', users: visible.value.filter(u => ['SUPER_ADMIN', 'ADMIN'].includes(u.role)) },
  { role: 'DISPATCHER' as const, title: '调度员', hint: '负责任务创建、分配和运输监控', users: visible.value.filter(u => u.role === 'DISPATCHER') },
  { role: 'DRIVER' as const, title: '司机', hint: '已通过注册审核的司机；待审核与被拒绝的申请请查看司机审核记录', users: visible.value.filter(u => u.role === 'DRIVER' && ['APPROVED', 'DISABLED'].includes(u.status)) },
])
const activeUsers = computed(() => userGroups.value.find(group => group.role === activeGroup.value)?.users ?? [])
const superAdmin = computed(() => account.value?.role === 'SUPER_ADMIN')
async function load() { try { account.value = await currentAccount(); users.value = await businessApi('/users') } catch (e) { error.value = e instanceof Error ? e.message : '读取失败。' } }
onMounted(load)
async function act(action: () => Promise<unknown>, text: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action(); editing.value = null; creating.value = false; password.value = ''; notice.value = text; await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '操作失败。' }
  finally { busy.value = false }
}
function edit(user: Person) { editing.value = user; name.value = user.displayName; phone.value = user.phone || ''; creating.value = false }
function save() { if (editing.value) act(() => businessApi(`/users/${editing.value!.id}/contact`, { displayName: name.value, phone: phone.value }), '资料已保存。') }
function create() { act(() => businessApi('/users', { username: username.value, password: password.value, displayName: name.value, phone: phone.value, role: newRole.value }), '职员账号已创建，可通过统一登录页登录。') }
function status(user: Person) { if (window.confirm(`确认${user.status === 'DISABLED' ? '启用' : '停用'} ${user.username}？该账号现有登录会话将失效。`)) act(() => businessApi(`/users/${user.id}/status`, { enabled: user.status === 'DISABLED' }), '账号状态已更新。') }
function role(user: Person) { const next = user.role === 'ADMIN' ? 'DISPATCHER' : 'ADMIN'; if (window.confirm(`将 ${user.username} 调整为${roles[next]}？现有登录会话将失效。`)) act(() => businessApi(`/users/${user.id}/role`, { role: next }), '角色已更新。') }
function remove(user: Person) {
  if (!window.confirm(`确认删除 ${user.username}？账号将不可登录，运输与审计历史保留。`)) return
  act(async () => { const r = await sessionFetch(`/api/admin/users/${user.id}`, { method: 'DELETE' }); if (!r.ok) { const data = await r.json().catch(() => null); throw new Error(data?.message ?? '删除失败。') } }, '账号已删除，运输历史保留。')
}
function chooseReset(user: Person) {
  resetting.value = user; identityVerified.value = false; temporaryPassword.value = ''; error.value = ''; notice.value = ''; editing.value = null; creating.value = false
}
async function resetPassword() {
  if (!resetting.value || !identityVerified.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = await businessApi<{ temporaryPassword: string }>(`/users/${resetting.value.id}/reset-password`, {})
    temporaryPassword.value = result.temporaryPassword
    notice.value = '司机密码已重置，原有登录会话已撤销。请将临时密码告知已核实身份的司机。'
  } catch (e) { error.value = e instanceof Error ? e.message : '重置失败。' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="section">
    <div class="section-title"><div><p class="kicker">USER DIRECTORY</p><h2>{{ account?.role === 'DISPATCHER' ? '司机资料' : '用户与角色管理' }}</h2></div><button class="outline-button" :disabled="busy" @click="load">刷新人员目录</button></div>
    <p class="source-note">此处仅展示已通过审核的司机（含已停用账号）。待审核与被拒绝的申请请前往司机审核页。普通管理员、调度员可维护司机联系方式；超级管理员可管理账号，并在核实司机身份后重置密码。运输中的司机受任务约束保护。</p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <section v-if="resetting" class="business-panel password-reset-panel" aria-label="司机密码重置">
      <h3>重置司机密码：{{ resetting.displayName }}（{{ resetting.username }}）</h3>
      <template v-if="temporaryPassword">
        <p>临时密码仅在此处显示，请通过已核实的联系方式告知司机。司机登录后必须先设置新密码。</p>
        <label>临时密码<input :value="temporaryPassword" readonly autocomplete="off" @focus="($event.target as HTMLInputElement).select()"></label>
        <p v-if="resetting.status === 'DISABLED'" class="source-note">该账号仍已停用，重置密码不会恢复登录权限。</p>
      </template>
      <form v-else class="business-form" @submit.prevent="resetPassword">
        <p>重置将撤销该司机所有现有登录。系统不会改变审核、停用或运输任务状态。</p>
        <label class="identity-check"><input v-model="identityVerified" type="checkbox" :disabled="busy" required>我已核实该司机身份，并确认其申请重置密码</label>
        <button class="primary-button" type="submit" :disabled="busy || !identityVerified">{{ busy ? '正在重置…' : '确认重置并生成临时密码' }}</button>
      </form>
      <button class="outline-button" type="button" :disabled="busy" @click="resetting = null; temporaryPassword = ''; identityVerified = false">{{ temporaryPassword ? '已记录，关闭' : '取消' }}</button>
    </section>
    <div class="driver-actions"><label class="business-filter">搜索姓名 / 用户名 / 联系方式<input v-model="search" type="search"></label><button v-if="superAdmin" class="primary-button" :disabled="busy" @click="creating = true; editing = null; name = ''; phone = ''; username = ''; password = ''">新建职员账号</button></div>
    <form v-if="creating || editing" class="business-form form-grid" @submit.prevent="creating ? create() : save()">
      <h3>{{ creating ? '新建管理员或调度员' : `编辑 ${editing?.username}` }}</h3>
      <template v-if="creating"><label>用户名<input v-model="username" required pattern="[A-Za-z0-9_.-]{3,32}" maxlength="32" autocomplete="off"></label><label>初始密码<input v-model="password" type="password" required minlength="10" maxlength="128" autocomplete="new-password"></label><label>职员角色<select v-model="newRole"><option value="DISPATCHER">调度员</option><option value="ADMIN">普通管理员</option></select></label></template>
      <label>姓名<input v-model="name" required maxlength="80"></label><label>联系方式<input v-model="phone" maxlength="32"></label>
      <div class="driver-actions"><button class="primary-button" :disabled="busy" type="submit">{{ busy ? '保存中…' : '保存资料' }}</button><button class="outline-button" type="button" :disabled="busy" @click="editing = null; creating = false; password = ''">取消</button></div>
    </form>
    <div class="user-switcher" role="tablist" aria-label="用户类型">
      <button v-for="group in userGroups" :key="group.role" class="user-switcher-button" :class="{ active: activeGroup === group.role }" role="tab" :aria-selected="activeGroup === group.role" @click="activeGroup = group.role"><strong>{{ group.title }}列表</strong><span>{{ group.users.length }} 人</span><i>⌄</i></button>
    </div>
    <section class="user-group active-user-group">
      <div class="user-group-heading"><div><h3>{{ userGroups.find(group => group.role === activeGroup)?.title }}列表</h3><p>{{ userGroups.find(group => group.role === activeGroup)?.hint }}</p></div><span>{{ activeUsers.length }} 人</span></div>
      <div class="user-directory"><article v-for="user in activeUsers" :key="user.id" class="user-directory-row"><div><strong>{{ user.displayName }}</strong><span>{{ user.username }} · {{ roles[user.role] }}</span><small>{{ user.phone || '未填写联系方式' }}</small></div><span class="detail-status" :class="{ verified: user.status === 'APPROVED' }">{{ statuses[user.status] }}{{ user.busy ? ' · 任务占用' : '' }}</span><div class="driver-actions"><button v-if="user.status !== 'DELETED' && (superAdmin || user.role === 'DRIVER')" class="outline-button" :disabled="busy" @click="edit(user)">编辑资料</button><template v-if="superAdmin && user.id !== account?.id && user.role !== 'SUPER_ADMIN' && user.status !== 'DELETED'"><button v-if="user.status === 'APPROVED' && ['ADMIN','DISPATCHER'].includes(user.role)" class="outline-button" :disabled="busy || user.busy" @click="role(user)">调整角色</button><button v-if="user.role === 'DRIVER' && ['APPROVED','DISABLED'].includes(user.status)" class="outline-button" :disabled="busy" @click="chooseReset(user)">重置密码</button><button v-if="['APPROVED','DISABLED'].includes(user.status)" class="outline-button" :disabled="busy || user.busy" @click="status(user)">{{ user.status === 'DISABLED' ? '启用' : '停用' }}</button><button class="outline-button danger" :disabled="busy || user.busy" @click="remove(user)">删除账号</button></template></div></article><div v-if="!activeUsers.length" class="empty-panel">该分类暂无符合条件的人员。</div></div>
    </section>
  </section>
</template>

<style scoped>
.password-reset-panel { margin-block: 1rem; }
.password-reset-panel .identity-check { display: flex; align-items: center; gap: .6rem; }
.password-reset-panel .identity-check input { width: 1rem; height: 1rem; margin: 0; flex: 0 0 auto; }
.password-reset-panel > button { margin-top: .8rem; }
.password-reset-panel > label { display: grid; gap: .5rem; }
.password-reset-panel > label input { width: 100%; max-width: 32rem; padding: .8rem; border: 1px solid #d5dfdd; border-radius: 8px; font-family: monospace; }
</style>
