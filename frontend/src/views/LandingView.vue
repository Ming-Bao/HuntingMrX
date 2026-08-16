<template>
  <div class="page">
    <div class="hero">
      <div class="hero-text">
        <h1 class="title">Hunting Mr. X</h1>
        <p class="subtitle">Wellington Edition</p>
        <p class="tagline">Hunt down Mr. X across Wellington's streets</p>
      </div>

      <div class="btn-group">
        <RouterLink to="/create" class="btn-primary">
          <Users :size="18" />
          Create Game
        </RouterLink>
        <RouterLink to="/join" class="btn-secondary">
          <UserPlus :size="18" />
          Join Game
        </RouterLink>
      </div>

      <p class="attribution">
        Game mechanics based on the board game by Ravensburger. This is a
        non-commercial student project and is not affiliated with or
        endorsed by Ravensburger.
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Users, UserPlus } from 'lucide-vue-next'
</script>

<style scoped>
@reference "tailwindcss";
@variant dark (&:is(.dark *));

.page {
  @apply min-h-screen bg-white dark:bg-gray-950 flex flex-col items-center justify-center px-4;
}
.hero {
  @apply text-center space-y-8 max-w-md w-full;
}
.hero-text {
  @apply space-y-2;
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
.btn-group {
  @apply flex flex-col gap-3;
}
.btn-primary {
  @apply flex items-center justify-center gap-2 bg-blue-600 hover:bg-blue-700
         text-white font-medium py-3 px-6 rounded-lg transition-colors;
}
.btn-secondary {
  @apply flex items-center justify-center gap-2 bg-gray-200 dark:bg-gray-700
         hover:bg-gray-300 dark:hover:bg-gray-600
         text-gray-800 dark:text-white font-medium py-3 px-6 rounded-lg transition-colors;
}
</style>
