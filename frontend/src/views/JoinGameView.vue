<template>
  <div class="page">
    <div class="content">
      <PageHeader title="Join Game" @back="router.push('/')" />

      <div class="card">
        <FormInput label="Your Name" v-model="playerName" placeholder="Enter your name" :maxlength="20" />

        <FormInput
          label="Game Code"
          v-model="joinCode"
          placeholder="XXXXXX"
          :maxlength="6"
          input-class="code-input"
          :uppercase="true"
        />

        <ErrorBanner :message="error" />

        <button @click="handleJoin" :disabled="loading" class="btn-primary">
          {{ loading ? 'Joining…' : 'Join Game' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { joinGame } from '../api/gameApi'
import { useGameStore } from '../stores/gameStore'
import PageHeader from '../components/ui/PageHeader.vue'
import FormInput from '../components/ui/FormInput.vue'
import ErrorBanner from '../components/ui/ErrorBanner.vue'

const route = useRoute()
const router = useRouter()
const store = useGameStore()

const playerName = ref('')
// Pre-filled when arriving via a shareable link (/:code, e.g.
// https://vuw-mrx.xyz/WXYZ12) instead of the plain /join form.
const joinCode = ref((route.params.code as string | undefined)?.toUpperCase() ?? '')
const loading = ref(false)
const error = ref('')

async function handleJoin() {
  if (!playerName.value.trim()) {
    error.value = 'Please enter your name'
    return
  }
  if (joinCode.value.length !== 6) {
    error.value = 'Game code must be 6 characters'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await joinGame(joinCode.value, playerName.value.trim())
    store.setGame(result.gameState.gameId, result.playerId, result.gameState)
    router.push(`/lobby/${result.gameState.gameId}`)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to join game'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";
@variant dark (&:is(.dark *));

.page {
  @apply min-h-screen bg-white dark:bg-gray-950 flex items-center justify-center px-4;
}
.content {
  @apply w-full max-w-md space-y-6;
}
.card {
  @apply bg-gray-100 dark:bg-gray-900 rounded-lg p-6 space-y-4;
}
.btn-primary {
  @apply w-full bg-blue-600 hover:bg-blue-700 disabled:opacity-50
         text-white font-medium py-3 rounded-lg transition-colors;
}
/* Applied via inputClass prop on the code input */
:deep(.code-input) {
  @apply font-mono text-xl tracking-widest uppercase;
}
</style>
