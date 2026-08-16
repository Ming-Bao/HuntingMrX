<template>
  <div class="page">
    <div class="content">
      <PageHeader title="Create Game" @back="router.push('/')" />

      <div class="card">
        <FormInput label="Your Name" v-model="hostName" placeholder="Enter your name" :maxlength="20" />

        <div class="field">
          <label class="field-label">Max Players</label>
          <select v-model="maxPlayers" class="select">
            <option v-for="n in [2, 3, 4, 5, 6]" :key="n" :value="n">{{ n }} players</option>
          </select>
        </div>

        <ErrorBanner :message="error" />

        <button @click="handleCreate" :disabled="loading" class="btn-primary">
          {{ loading ? 'Creating…' : 'Create Game' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createGame } from '../api/gameApi'
import { useGameStore } from '../stores/gameStore'
import PageHeader from '../components/ui/PageHeader.vue'
import FormInput from '../components/ui/FormInput.vue'
import ErrorBanner from '../components/ui/ErrorBanner.vue'

const router = useRouter()
const store = useGameStore()

const hostName = ref('')
const maxPlayers = ref(4)
const loading = ref(false)
const error = ref('')

async function handleCreate() {
  if (!hostName.value.trim()) {
    error.value = 'Please enter your name'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await createGame(hostName.value.trim(), maxPlayers.value)
    store.setGame(result.gameState.gameId, result.playerId, result.gameState)
    router.push(`/lobby/${result.gameState.gameId}`)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create game'
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
.field {
  @apply space-y-1;
}
.field-label {
  @apply text-sm text-gray-600 dark:text-gray-400;
}
.select {
  @apply w-full bg-white dark:bg-gray-800 text-gray-900 dark:text-white
         border border-gray-300 dark:border-transparent
         rounded-lg px-4 py-2.5 outline-none focus:ring-2 focus:ring-blue-600;
}
.btn-primary {
  @apply w-full bg-blue-600 hover:bg-blue-700 disabled:opacity-50
         text-white font-medium py-3 rounded-lg transition-colors;
}
</style>
