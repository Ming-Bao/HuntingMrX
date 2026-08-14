<template>
  <div class="page">
    <div class="content">

      <!-- Kicked banner -->
      <div v-if="kicked" class="kicked-banner">
        <p class="kicked-title">You were kicked</p>
        <p class="kicked-message">The host removed you from the lobby.</p>
        <RouterLink to="/" class="kicked-link">Return to home</RouterLink>
      </div>

      <!-- Abort banner -->
      <div v-else-if="aborted" class="abort-banner">
        <p class="abort-title">Game ended</p>
        <p class="abort-message">{{ abortMessage }}</p>
        <RouterLink to="/" class="abort-link">Back to home</RouterLink>
      </div>

      <template v-else-if="!kicked && !aborted">
        <PageHeader title="Game Lobby" @back="handleLeave" />

        <JoinCodeCard :code="gameState?.joinCode ?? ''" />

        <PlayerSlotList
          :players="gameState?.players ?? []"
          :max-players="gameState?.maxPlayers ?? 0"
          :host-player-id="isHost ? store.playerId ?? undefined : undefined"
          @kick="handleKick"
        />

        <!-- Start / waiting -->
        <div v-if="isHost" class="start-section">
          <ErrorBanner :message="startError" />
          <button
            @click="handleStart"
            :disabled="!canStart || starting"
            class="btn-start"
          >
            {{ starting ? 'Starting…' : 'Start Game' }}
          </button>
          <p v-if="!canStart" class="start-hint">Need at least 2 players to start</p>
        </div>
        <div v-else class="waiting-text">
          Waiting for the host to start the game…
        </div>

        <button @click="handleLeave" class="btn-leave">Leave Game</button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { startGame, leaveGame, kickPlayer, getGame } from '../api/gameApi'
import { WS_PATH } from '../utils/basePath'
import { useGameStore } from '../stores/gameStore'
import type { GameStateDTO } from '../types/game'
import PageHeader from '../components/ui/PageHeader.vue'
import ErrorBanner from '../components/ui/ErrorBanner.vue'
import JoinCodeCard from '../components/lobby/JoinCodeCard.vue'
import PlayerSlotList from '../components/lobby/PlayerSlotList.vue'

const route = useRoute()
const router = useRouter()
const store = useGameStore()

const gameId = computed(() => route.params.id as string)
const gameState = computed(() => store.gameState)
const isHost = computed(() => !!store.playerId && store.playerId === store.gameState?.players[0]?.id)
const canStart = computed(() => (gameState.value?.players.length ?? 0) >= 2)

const starting = ref(false)
const startError = ref('')
const kicked = ref(false)
const aborted = ref(false)
const abortMessage = ref('')

let stompClient: Client | null = null
let pollHandle: number | null = null

// Shared by the live subscription and the reconnect catch-up fetch below —
// same "what does this state mean for me" branching either way.
function applyState(state: GameStateDTO): void {
  const stillInGame = state.players.some(p => p.id === store.playerId)
  if (!stillInGame && state.phase === 'LOBBY') {
    stompClient?.deactivate()
    store.clearGame()
    kicked.value = true
    return
  }
  store.updateGameState(state)
  if (state.phase === 'IN_PROGRESS') {
    stompClient?.deactivate()
    router.push(`/game/${gameId.value}`)
  } else if (state.phase === 'ENDED') {
    stompClient?.deactivate()
    aborted.value = true
    abortMessage.value = state.abortReason ?? 'The game has ended'
  }
}

onMounted(() => {
  if (gameId.value === 'preview') return
  stompClient = new Client({
    webSocketFactory: () => new SockJS(WS_PATH),
    onConnect: async () => {
      // Catch-up fetch on every (re)connect, not just the first — the host
      // can click Start in the exact window a connection is dropping, and
      // the /topic broadcast for it is a one-shot push with no replay: if a
      // client isn't connected at that instant, nothing ever resends it and
      // that player is stuck in the lobby forever even though the game is
      // running fine server-side. A REST re-check here means a reconnect
      // (STOMP retries automatically) always catches up regardless of what
      // was missed while disconnected.
      if (store.playerId) {
        try {
          applyState(await getGame(gameId.value, store.playerId))
        } catch {
          // Ignore — the live subscription below still has a shot at it,
          // and this was just a best-effort catch-up.
        }
      }
      stompClient!.subscribe(`/topic/games/${gameId.value}`, (msg) => {
        applyState(JSON.parse(msg.body))
      })
    },
    // No onDisconnect handler: a dropped WebSocket is not the same thing as
    // the game ending, and this network drops connections often enough that
    // treating every blip as "Game ended" was stranding players who were
    // actually fine a few seconds later once STOMP's automatic reconnect
    // (and the catch-up fetch above) kicked back in. A real abort is a
    // phase: 'ENDED' state from the server, handled in applyState.
  })
  stompClient.activate()

  // Belt-and-suspenders poll, independent of the WebSocket's own notion of
  // connection state — the reconnect-triggered catch-up above only fires on
  // an actual disconnect→reconnect cycle. A connection can also silently
  // drop just the one "game started" broadcast without ever crossing
  // whatever threshold counts as "disconnected," in which case nothing
  // currently prompts a re-sync. This bounds that staleness to one poll
  // interval regardless of what the WebSocket layer thinks is happening.
  pollHandle = window.setInterval(async () => {
    if (!store.playerId || gameId.value === 'preview') return
    try {
      applyState(await getGame(gameId.value, store.playerId))
    } catch { /* best-effort, try again next tick */ }
  }, 6000)
})

onUnmounted(() => {
  stompClient?.deactivate()
  if (pollHandle !== null) window.clearInterval(pollHandle)
})

async function handleStart() {
  if (!store.playerId || !gameId.value) return
  starting.value = true
  startError.value = ''
  try {
    const state = await startGame(gameId.value, store.playerId)
    store.updateGameState(state)
    router.push(`/game/${gameId.value}`)
  } catch (e: unknown) {
    startError.value = e instanceof Error ? e.message : 'Failed to start game'
  } finally {
    starting.value = false
  }
}

async function handleKick(targetPlayerId: string) {
  if (!store.playerId || !gameId.value) return
  try {
    await kickPlayer(gameId.value, store.playerId, targetPlayerId)
  } catch (e: unknown) {
    startError.value = e instanceof Error ? e.message : 'Failed to kick player'
  }
}

async function handleLeave() {
  stompClient?.deactivate()
  const id = gameId.value
  const pid = store.playerId
  if (id && pid && id !== 'preview') {
    try { await leaveGame(id, pid) } catch { /* ignore */ }
  }
  store.clearGame()
  router.push('/')
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
.kicked-banner {
  @apply bg-orange-900/20 border border-orange-700 rounded-lg px-4 py-6 text-center space-y-3;
}
.kicked-title {
  @apply text-orange-400 font-semibold text-lg;
}
.kicked-message {
  @apply text-orange-300 text-sm;
}
.kicked-link {
  @apply inline-block mt-1 bg-gray-200 dark:bg-gray-800 hover:bg-gray-300 dark:hover:bg-gray-700
         text-gray-800 dark:text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors;
}
.abort-banner {
  @apply bg-red-900/20 border border-red-700 rounded-lg px-4 py-4 text-center space-y-2;
}
.abort-title {
  @apply text-red-400 font-medium;
}
.abort-message {
  @apply text-red-300 text-sm;
}
.abort-link {
  @apply inline-block mt-2 text-sm text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white transition-colors;
}
.start-section {
  @apply space-y-2;
}
.btn-start {
  @apply w-full bg-green-600 hover:bg-green-700 disabled:opacity-40 disabled:cursor-not-allowed
         text-white font-medium py-3 rounded-lg transition-colors;
}
.start-hint {
  @apply text-gray-500 dark:text-gray-600 text-xs text-center;
}
.waiting-text {
  @apply text-center text-gray-500 text-sm py-2;
}
.btn-leave {
  @apply w-full bg-gray-200 dark:bg-gray-800 hover:bg-gray-300 dark:hover:bg-gray-700
         text-gray-800 dark:text-white text-sm font-medium py-2.5 rounded-lg transition-colors;
}
</style>
