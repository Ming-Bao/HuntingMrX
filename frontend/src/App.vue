<template>
  <!-- The game board has no room for the floating toggle, so it's hidden there -->
  <ThemeToggle v-if="!route.path.startsWith('/game/')" />
  <RouterView />
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import ThemeToggle from './components/ThemeToggle.vue'

const route = useRoute()
</script>
