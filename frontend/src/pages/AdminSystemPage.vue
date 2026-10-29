<script setup lang="ts">
import { useWorkspace } from '../workspace'
const { components } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <section id="components" class="section component-strip"><div class="section-title"><div><p class="kicker">PLATFORM STATUS</p><h2>平台组件</h2></div></div><div class="service-grid"><article v-for="service in components" :key="service.name" class="service-card"><div class="service-heading"><span class="service-name">{{ service.name }}</span><span class="status-chip" :class="service.status === 'CONNECTED' ? 'ok' : 'pending'"><i></i>{{ service.status === 'CONNECTED' ? '已连接' : '不可用' }}</span></div><strong>{{ service.endpoint }}</strong><small>{{ service.detail }}</small></article></div></section>
  </div>
</template>
