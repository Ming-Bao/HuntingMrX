<template>
  <div class="header">
    <button @click="$emit('back')" class="icon-button" title="Back">
      <ArrowLeft :size="20" />
    </button>
    <h1 class="title">{{ title }}</h1>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft } from 'lucide-vue-next'
defineProps<{ title: string }>()
defineEmits<{ back: [] }>()
</script>

<style scoped>
@reference "../app/style.css";

.header {
  @apply flex items-center gap-3;
}
.title {
  @apply text-2xl font-bold text-gray-900 dark:text-white;
}
</style>
