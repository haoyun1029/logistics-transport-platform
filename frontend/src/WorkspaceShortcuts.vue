<script setup lang="ts">
import { navigation } from './navigation'
import type { Portal } from './auth'
defineProps<{ portal: Portal }>()
</script>

<template>
  <section class="section">
    <div class="section-title"><div><p class="kicker">QUICK ACCESS</p><h2>常用功能</h2></div><span class="muted">选择功能进入独立页面</span></div>
    <div class="shortcut-grid">
      <a v-for="item in navigation[portal].slice(1)" :key="item.path" :href="item.path" class="shortcut-card">
        <strong>{{ item.title }} <span aria-hidden="true">↗</span></strong><small>{{ item.description }}</small>
      </a>
    </div>
  </section>
</template>
