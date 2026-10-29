<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { currentAccount, sessionFetch } from '../auth'
const emit = defineEmits<{ changed: [] }>()
const superAdmin = ref(false)
type PendingDriver = { id: number; username: string; displayName: string; phone: string | null; registeredAt: string }
type DriverReview = { id: number; username: string; displayName: string; status: string; note: string | null; reviewedAt: string; reviewerName: string | null }
const pending = ref<PendingDriver[]>([])
const reviews = ref<DriverReview[]>([])
const loading = ref(false)
const loaded = ref(false)
const historyLoaded = ref(false)
const sending = ref(false)
const error = ref('')
const historyError = ref('')
const notice = ref('')
const selection = ref<{ driver: PendingDriver; approve: boolean } | null>(null)
const note = ref('')
const dialog = ref<HTMLDialogElement>()
watch(selection, async value => { if (value) { await nextTick(); if (!dialog.value?.open) dialog.value?.showModal() } })
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
async function readJson<T>(url: string): Promise<T> {
  const response = await sessionFetch(url)
  const data = await response.json().catch(() => null)
  if (!response.ok) throw new Error(data?.message ?? '读取失败，请重试。')
  return data as T
}
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''; historyError.value = ''
  const results = await Promise.allSettled([
    readJson<PendingDriver[]>('/api/admin/drivers/pending'),
    readJson<DriverReview[]>('/api/admin/drivers/reviews'),
  ])
  if (results[0].status === 'fulfilled') { pending.value = results[0].value; loaded.value = true }
  else error.value = results[0].reason instanceof Error ? results[0].reason.message : '申请列表读取失败。'
  if (results[1].status === 'fulfilled') { reviews.value = results[1].value; historyLoaded.value = true }
  else historyError.value = results[1].reason instanceof Error ? results[1].reason.message : '审核记录读取失败。'
  loading.value = false
}
function choose(driver: PendingDriver, approve: boolean) {
  selection.value = { driver, approve }; note.value = ''; error.value = ''; notice.value = ''
}
async function submitReview() {
  if (!selection.value || sending.value) return
  if (!selection.value.approve && !note.value.trim()) { error.value = '拒绝申请时请填写原因。'; return }
  sending.value = true; error.value = ''
  const { driver, approve } = selection.value
  try {
    const response = await sessionFetch(`/api/admin/drivers/${driver.id}/review`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approve, note: note.value.trim() }),
    })
    if (!response.ok) {
      const data = await response.json().catch(() => null)
      if (response.status === 409) { selection.value = null; await load() }
      throw new Error(data?.message ?? '审核失败，请重试。')
    }
    notice.value = `${driver.displayName}（${driver.username}）的注册申请已${approve ? '通过' : '拒绝'}。`
    selection.value = null
    await load()
    emit('changed')
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '审核失败。' }
  finally { sending.value = false }
}
async function removeRejected(review: DriverReview) {
  if (sending.value || review.status !== 'REJECTED') return
  if (!window.confirm(`确认删除被拒绝的申请 ${review.username}？账号将不可登录，审计历史保留。`)) return
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const response = await sessionFetch(`/api/admin/users/${review.id}`, { method: 'DELETE' })
    if (!response.ok) {
      const data = await response.json().catch(() => null)
      throw new Error(data?.message ?? '删除失败。')
    }
    notice.value = `${review.username} 的账号已删除。`
    await load(); emit('changed')
  } catch (e) { error.value = e instanceof Error ? e.message : '删除失败。' }
  finally { sending.value = false }
}
onMounted(async () => {
  try { superAdmin.value = (await currentAccount()).role === 'SUPER_ADMIN' }
  catch (e) { error.value = e instanceof Error ? e.message : '读取权限失败。' }
  await load()
})
</script>

<template>
  <div class="portal-content">
    <section class="section">
      <div class="section-title"><div><p class="kicker">ACCESS CONTROL</p><h2>待审核司机</h2></div><button class="outline-button" :disabled="loading || sending" @click="load">{{ loading ? '正在读取…' : '刷新审核列表' }}</button></div>
      <p class="source-note">司机通过注册页面提交申请；审核通过后才能登录。审核结果、备注、操作人和时间保存在 MySQL。</p>
      <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
      <p v-if="error && !selection" class="alert error" role="alert">{{ error }}{{ loaded ? ' 当前保留上次列表。' : '' }}</p>
      <p v-if="loading && !loaded" role="status">正在读取注册申请…</p>
      <div v-if="pending.length" class="review-cards">
        <article v-for="driver in pending" :key="driver.id" class="review-card">
          <div><h3>{{ driver.displayName }}</h3><span class="tag">{{ driver.username }} · #{{ driver.id }}</span></div>
          <dl><div><dt>联系方式</dt><dd>{{ driver.phone || '未填写' }}</dd></div><div><dt>申请时间</dt><dd>{{ date(driver.registeredAt) }}</dd></div></dl>
          <div class="review-actions"><button class="primary-button" :disabled="sending" @click="choose(driver, true)">通过 {{ driver.username }}</button><button class="outline-button review-reject" :disabled="sending" @click="choose(driver, false)">拒绝 {{ driver.username }}</button></div>
        </article>
      </div>
      <div v-else-if="loaded && !loading && !error" class="empty-panel"><strong>暂无待审核申请</strong><p>新的司机注册申请会在此显示。</p></div>
    </section>
    <section class="section review-history">
      <div class="section-title"><div><p class="kicker">REVIEW HISTORY</p><h2>最近审核记录</h2></div><span class="muted">最近 100 条 · MySQL</span></div>
      <p v-if="historyError" class="alert error" role="alert">{{ historyError }}{{ historyLoaded ? ' 当前保留上次记录。' : '' }}</p>
      <div v-if="reviews.length" class="review-history-list"><article v-for="review in reviews" :key="review.id" class="review-history-item"><div><strong>{{ review.displayName }}</strong><small>{{ review.username }}</small></div><span :class="review.status === 'APPROVED' ? 'verified' : 'unverified'">{{ review.status === 'APPROVED' ? '已通过' : '已拒绝' }}</span><div><strong>{{ review.reviewerName || '—' }}</strong><small>{{ date(review.reviewedAt) }}</small></div><div><p>{{ review.note || '未填写备注' }}</p><button v-if="superAdmin && review.status === 'REJECTED'" class="outline-button danger" :disabled="sending || loading" @click="removeRejected(review)">删除被拒绝的账号</button></div></article></div>
      <div v-else-if="historyLoaded && !historyError" class="empty-panel"><strong>暂无审核记录</strong><p>完成审核后可查看结果和备注。</p></div>
    </section>
    <dialog v-if="selection" ref="dialog" class="review-dialog" aria-labelledby="review-title" @cancel.prevent="!sending && (selection = null)">
        <h2 id="review-title">{{ selection.approve ? '确认通过注册申请' : '确认拒绝注册申请' }}</h2>
        <p>{{ selection.driver.displayName }}（{{ selection.driver.username }}）</p><p class="muted">{{ selection.approve ? '通过后，该账号可以登录司机端。' : '拒绝后，该账号无法登录司机端。' }}</p>
        <form @submit.prevent="submitReview"><label for="review-note">{{ selection.approve ? '审核备注（选填）' : '拒绝原因（必填）' }}</label><textarea id="review-note" v-model="note" maxlength="300" :required="!selection.approve" :disabled="sending" rows="4"></textarea><small>{{ note.length }} / 300</small><p v-if="error" class="alert error" role="alert">{{ error }}</p><div class="review-actions"><button class="outline-button" type="button" :disabled="sending" @click="selection = null">返回列表</button><button class="primary-button" type="submit" :disabled="sending">{{ sending ? '正在提交…' : selection.approve ? '确认通过' : '确认拒绝' }}</button></div></form>
    </dialog>
  </div>
</template>
