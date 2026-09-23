<template>
  <div class="page">
    <div class="page-column">
      <!-- Kicked by the host, or the game ended (e.g. the host left) -->
      <div v-if="ended" class="ended-banner" :class="`ended-banner--${ended.tone}`">
        <p class="ended-title">{{ ended.title }}</p>
        <p class="ended-message">{{ ended.message }}</p>
        <RouterLink to="/" class="button button-secondary inline-flex mt-1 px-4 py-2 text-sm">Back to home</RouterLink>
      </div>

      <template v-else>
        <PageHeader title="Game Lobby" @back="leave" />

        <JoinCodeCard :code="info?.joinCode ?? ''" />

        <PlayerList
          :players="info?.players ?? []"
          :max-players="info?.maxPlayers ?? 0"
          :can-kick="isHost"
          @kick="kick"
        />

        <div v-if="isHost" class="space-y-2">
          <p v-if="startError" class="error-banner">{{ startError }}</p>
          <button @click="start" :disabled="!canStart || starting" class="button button-start w-full py-3">
            {{ starting ? 'Starting…' : 'Start Game' }}
          </button>
          <p v-if="!canStart" class="start-hint">Need at least 2 players to start</p>
        </div>
        <div v-else class="waiting-text">
          Waiting for the host to start the game…
        </div>

        <button @click="leave" class="button button-secondary w-full py-2.5 text-sm">Leave Game</button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { startGame, removePlayer, getGame, keepUpToDate } from '../shared/api'
import { currentGame, updateGameInfo, forgetGame, leaveGame } from '../shared/current-game'
import type { GameInfo } from '../shared/types'
import PageHeader from '../components/PageHeader.vue'
import JoinCodeCard from '../components/lobby/JoinCodeCard.vue'
import PlayerList from '../components/lobby/PlayerList.vue'

const route = useRoute()
const router = useRouter()

const gameId = route.params.id as string
const info = computed(() => currentGame.info)
// The server always keeps the host first in the list
const isHost = computed(() => !!currentGame.myPlayerId && currentGame.myPlayerId === info.value?.players[0]?.id)
const canStart = computed(() => (info.value?.players.length ?? 0) >= 2)

const starting = ref(false)
const startError = ref('')
const ended = ref<{ title: string; message: string; tone: 'kicked' | 'aborted' } | null>(null)

let stopUpdates = () => {}

// Handles new game info, whether it arrived live or from a refresh
function onGameUpdate(state: GameInfo) {
  // Gone from the list while the game is still in the lobby: the host kicked us
  if (state.phase === 'LOBBY' && !state.players.some(player => player.id === currentGame.myPlayerId)) {
    stopUpdates()
    forgetGame()
    ended.value = { title: 'You were kicked', message: 'The host removed you from the lobby.', tone: 'kicked' }
    return
  }
  updateGameInfo(state)
  if (state.phase === 'IN_PROGRESS') {
    stopUpdates()
    router.push(`/game/${gameId}`)
  } else if (state.phase === 'ENDED') {
    stopUpdates()
    ended.value = { title: 'Game ended', message: state.abortReason ?? 'The game has ended', tone: 'aborted' }
  }
}

async function refresh() {
  if (!currentGame.mySecretKey) return
  try { onGameUpdate(await getGame(gameId, currentGame.mySecretKey)) } catch { /* try again next time */ }
}

// A dropped connection is not the game ending: it reconnects on its own,
// and a real end arrives as phase 'ENDED' in onGameUpdate.
onMounted(() => {
  stopUpdates = keepUpToDate({ [`/topic/games/${gameId}`]: onGameUpdate }, refresh)
})
onUnmounted(() => stopUpdates())

async function start() {
  if (!currentGame.mySecretKey) return
  starting.value = true
  startError.value = ''
  try {
    updateGameInfo(await startGame(gameId, currentGame.mySecretKey))
    router.push(`/game/${gameId}`)
  } catch (problem: unknown) {
    startError.value = problem instanceof Error ? problem.message : 'Failed to start game'
  } finally {
    starting.value = false
  }
}

async function kick(playerToRemove: string) {
  if (!currentGame.mySecretKey) return
  try {
    await removePlayer(gameId, currentGame.mySecretKey, playerToRemove)
  } catch (problem: unknown) {
    startError.value = problem instanceof Error ? problem.message : 'Failed to kick player'
  }
}

async function leave() {
  stopUpdates()
  await leaveGame()
  router.push('/')
}
</script>

<style scoped>
@reference "../app/style.css";

.ended-banner {
  @apply border rounded-lg px-4 py-6 text-center space-y-3;
}
.ended-banner--kicked  { @apply bg-orange-900/20 border-orange-700; }
.ended-banner--aborted { @apply bg-red-900/20 border-red-700; }
.ended-title {
  @apply font-semibold text-lg;
}
.ended-message {
  @apply text-sm;
}
.ended-banner--kicked .ended-title    { @apply text-orange-400; }
.ended-banner--kicked .ended-message  { @apply text-orange-300; }
.ended-banner--aborted .ended-title   { @apply text-red-400; }
.ended-banner--aborted .ended-message { @apply text-red-300; }
.button-start {
  @apply bg-green-600 hover:bg-green-700 text-white;
}
.start-hint {
  @apply text-gray-500 dark:text-gray-600 text-xs text-center;
}
.waiting-text {
  @apply text-center text-gray-500 text-sm py-2;
}
</style>
