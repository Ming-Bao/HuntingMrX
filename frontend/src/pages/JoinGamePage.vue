<template>
  <div class="page">
    <div class="page-column">
      <PageHeader title="Join Game" @back="router.push('/')" />

      <form class="card space-y-4" @submit.prevent="join">
        <label class="block">
          <span class="form-label">Your Name</span>
          <input v-model="playerName" class="form-input" placeholder="Enter your name" maxlength="20" />
        </label>

        <label class="block">
          <span class="form-label">Game Code</span>
          <!-- Shown in capitals; the server upper-cases the code itself -->
          <input
            v-model="joinCode"
            class="form-input font-mono text-xl tracking-widest uppercase"
            placeholder="XXXXXX"
            maxlength="6"
          />
        </label>

        <p v-if="error" class="error-banner">{{ error }}</p>

        <button type="submit" :disabled="loading" class="button button-primary w-full py-3">
          {{ loading ? 'Joining…' : 'Join Game' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { joinGame } from '../shared/api'
import { rememberGame } from '../shared/current-game'
import PageHeader from '../components/PageHeader.vue'

const route = useRoute()
const router = useRouter()

const playerName = ref('')
// Filled in when arriving through a shareable link (/:code)
const joinCode = ref((route.params.code as string | undefined)?.toUpperCase() ?? '')
const loading = ref(false)
const error = ref('')

async function join() {
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
    rememberGame(result.gameState.gameId, result.playerId, result.playerToken, result.gameState)
    router.push(`/lobby/${result.gameState.gameId}`)
  } catch (problem: unknown) {
    error.value = problem instanceof Error ? problem.message : 'Failed to join game'
  } finally {
    loading.value = false
  }
}
</script>
