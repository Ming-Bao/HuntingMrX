<template>
  <div class="page">
    <div class="page-column">
      <PageHeader title="Create Game" @back="router.push('/')" />

      <form class="card space-y-4" @submit.prevent="create">
        <label class="block">
          <span class="form-label">Your Name</span>
          <input v-model="hostName" class="form-input" placeholder="Enter your name" maxlength="20" />
        </label>

        <label class="block">
          <span class="form-label">Max Players</span>
          <select v-model="maxPlayers" class="form-input">
            <option v-for="n in [2, 3, 4, 5, 6]" :key="n" :value="n">{{ n }} players</option>
          </select>
        </label>

        <p v-if="error" class="error-banner">{{ error }}</p>

        <button type="submit" :disabled="loading" class="button button-primary w-full py-3">
          {{ loading ? 'Creating…' : 'Create Game' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createGame } from '../shared/api'
import { rememberGame } from '../shared/current-game'
import PageHeader from '../components/PageHeader.vue'

const router = useRouter()

const hostName = ref('')
const maxPlayers = ref(4)
const loading = ref(false)
const error = ref('')

async function create() {
  if (!hostName.value.trim()) {
    error.value = 'Please enter your name'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await createGame(hostName.value.trim(), maxPlayers.value)
    rememberGame(result.gameState.gameId, result.playerId, result.playerToken, result.gameState)
    router.push(`/lobby/${result.gameState.gameId}`)
  } catch (problem: unknown) {
    error.value = problem instanceof Error ? problem.message : 'Failed to create game'
  } finally {
    loading.value = false
  }
}
</script>
