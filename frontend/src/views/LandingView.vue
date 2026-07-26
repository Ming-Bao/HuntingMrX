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

    <!-- Dev nav -->
    <div class="dev-nav">
      <div class="dev-nav-inner">
        <span class="dev-label">DevNav</span>
        <RouterLink to="/" class="dev-link">Landing</RouterLink>
        <RouterLink to="/create" class="dev-link">Create</RouterLink>
        <RouterLink to="/join" class="dev-link">Join</RouterLink>
        <RouterLink to="/lobby/preview" class="dev-link">Lobby</RouterLink>
        <RouterLink to="/game/preview" class="dev-link">Game Board</RouterLink>
      </div>
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
.dev-nav {
  @apply absolute bottom-4 left-0 right-0 flex justify-center;
}
.dev-nav-inner {
  @apply border border-gray-200 dark:border-gray-800 rounded-lg px-4 py-2 flex items-center gap-1;
}
.dev-label {
  @apply text-gray-400 dark:text-gray-600 text-xs mr-2;
}
.dev-link {
  @apply text-gray-500 hover:text-gray-800 dark:hover:text-gray-300 text-xs px-2 py-1 rounded
         hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors;
}
</style>
