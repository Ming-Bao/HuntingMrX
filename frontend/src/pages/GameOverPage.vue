<template>
  <div class="end-page">
    <div class="content">
      <Trophy :size="64" :class="result.color" />

      <div class="banner" :class="result.bg">{{ result.banner }}</div>

      <div class="summary-card">
        <div class="summary-row">
          <span class="summary-label">Game Code</span>
          <span class="summary-value font-mono">{{ info?.joinCode ?? '—' }}</span>
        </div>
        <div class="summary-row">
          <span class="summary-label">Rounds Played</span>
          <span class="summary-value">{{ round }}</span>
        </div>
        <div class="summary-row">
          <span class="summary-label">Result</span>
          <span class="summary-value">{{ result.short }}</span>
        </div>
      </div>

      <p class="narrative">{{ result.story }}</p>

      <div class="flex gap-3 w-full">
        <RouterLink to="/" class="button button-home flex-1 py-2.5 text-sm">Back to Home</RouterLink>
        <RouterLink to="/create" class="button button-primary flex-1 py-2.5 text-sm">Play Again</RouterLink>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Trophy } from 'lucide-vue-next'
import { currentGame } from '../shared/current-game'

// Shows the final game the board saved in currentGame. That isn't kept across
// a page refresh, so a refreshed end page falls back to plain "Game Over".
const info = computed(() => currentGame.info)
const round = computed(() => info.value?.round ?? 0)

const result = computed(() => {
  const winner = info.value?.winner
  if (winner === 'MR_X') return {
    color: 'text-red-500', bg: 'bg-red-600', banner: 'Mr. X Escaped!', short: 'Mr. X wins',
    story: `Mr. X survived all ${round.value} rounds undetected.`,
  }
  if (winner === 'DETECTIVES') return {
    color: 'text-blue-500', bg: 'bg-blue-600', banner: 'Detectives Win!', short: 'Detectives win',
    story: `The detectives caught Mr. X on round ${round.value}.`,
  }
  // No winner: someone left or the game timed out
  return {
    color: 'text-blue-500', bg: 'bg-blue-600', banner: 'Game Over',
    short: info.value?.abortReason ?? 'Aborted',
    story: info.value?.abortReason ?? 'The game ended unexpectedly.',
  }
})
</script>

<style scoped>
@reference "../app/style.css";

/* Always dark, whatever the theme */
.end-page {
  @apply min-h-screen bg-gray-950 flex items-center justify-center px-4;
}
.content {
  @apply flex flex-col items-center gap-6 w-full max-w-md text-center;
}
.banner {
  @apply w-full text-white font-bold text-lg py-3 rounded-full;
}
.summary-card {
  @apply w-full bg-gray-900 rounded-lg divide-y divide-gray-800;
}
.summary-row {
  @apply flex justify-between items-center px-5 py-3;
}
.summary-label { @apply text-sm text-gray-500; }
.summary-value { @apply text-sm text-white font-medium; }
.narrative {
  @apply text-sm text-gray-400 italic bg-gray-800 rounded-lg px-4 py-3 w-full;
}
.button-home {
  @apply bg-gray-800 hover:bg-gray-700 text-white;
}
</style>
