<script setup lang="ts">
import UserDirectory from '../components/UserDirectory.vue'
import AdminDriversPage from './AdminDriversPage.vue'
import { ref } from 'vue'
const directoryVersion = ref(0)
</script>

<template>
  <section>
    <UserDirectory :key="directoryVersion" />
    <details class="business-panel account-review-details">
      <summary>司机注册审核与记录</summary>
      <AdminDriversPage @changed="directoryVersion++" />
    </details>
  </section>
</template>
