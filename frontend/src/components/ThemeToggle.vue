<template>
  <button
    @click="toggle"
    class="toggle-button"
    :title="isDark ? 'Switch to light mode' : 'Switch to dark mode'"
  >
    <Sun v-if="isDark" :size="18" />
    <Moon v-else :size="18" />
  </button>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Sun, Moon } from 'lucide-vue-next'

// main.ts sets the starting theme; this flips it and remembers the choice.
const isDark = ref(document.documentElement.classList.contains('dark'))

function toggle() {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('dark', isDark.value)
  localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
}
</script>

<style scoped>
@reference "../app/style.css";

.toggle-button {
  @apply fixed top-4 right-4 z-50 p-2 rounded-lg
         bg-gray-100 dark:bg-gray-800
         text-gray-600 dark:text-gray-400
         hover:text-gray-900 dark:hover:text-white
         transition-colors;
}
</style>
