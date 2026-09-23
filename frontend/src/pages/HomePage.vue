<template>
  <div class="page">
    <div class="hero">
      <div class="space-y-2">
        <h1 class="title">Hunting Mr. X</h1>
        <p class="subtitle">Wellington Edition</p>
        <p class="tagline">Hunt down Mr. X across Wellington's streets</p>
      </div>

      <div class="flex flex-col gap-3">
        <RouterLink to="/create" class="button button-primary py-3 px-6">
          <Users :size="18" />
          Create Game
        </RouterLink>
        <RouterLink to="/join" class="button button-secondary py-3 px-6">
          <UserPlus :size="18" />
          Join Game
        </RouterLink>
      </div>

      <p class="attribution">
        Game mechanics based on the board game by Ravensburger. This is a
        non-commercial student project and is not affiliated with Ravensburger.
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Users, UserPlus } from 'lucide-vue-next'
</script>

<style scoped>
@reference "../app/style.css";

.hero {
  @apply text-center space-y-8 max-w-md w-full;
}
.title {
  @apply text-4xl font-bold text-gray-900 dark:text-white;
}
.subtitle {
  @apply text-blue-400 italic text-lg;
}
.tagline {
  @apply text-gray-600 dark:text-gray-400 text-sm;
}
.attribution {
  @apply text-gray-400 dark:text-gray-600 text-xs max-w-xs mx-auto;
}
</style>
