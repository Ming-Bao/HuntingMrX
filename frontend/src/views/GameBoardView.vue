<template>
  <div class="page">
    <!-- Header -->
    <div class="header">
      <div class="header-left">
        <RouterLink to="/" class="back-btn"><ArrowLeft :size="18" /></RouterLink>
        <h1 class="game-title">Scotland Yard</h1>
        <span class="game-subtitle">Wellington Edition</span>
        <span v-if="store.myRole" class="role-badge" :class="store.myRole === 'MR_X' ? 'role-badge--mrx' : 'role-badge--detective'">
          {{ store.myRole === 'MR_X' ? 'Mr. X' : 'Detective' }}
        </span>
      </div>
      <div class="header-right">
        <span class="round-label">
          Round <span class="round-num">{{ gameState?.round ?? 1 }}</span> / 24
        </span>
        <span class="turn-badge" :class="turnBadgeClass">{{ turnLabel }}</span>
        <span v-if="gameState?.mrXDoubleMovePending" class="double-badge">Double Move — 2nd leg</span>
      </div>
    </div>

    <!-- Map load error -->
    <div v-if="mapError" class="map-error">{{ mapError }}</div>

    <!-- Body -->
    <div class="body">
      <GameMap
        :nodes="nodes"
        :edges="edges"
        :display-players="displayPlayers"
        :selected-node="selectedNode"
        :reachable-ids="reachableNodeIds"
        @select-node="handleSelectNode"
      />

      <InfoPanel
        :players="displayPlayers"
        :tickets="myTickets"
        :mr-x-log="gameState?.mrXLog ?? []"
        :selected-node="selectedNode"
        :selected-ticket="selectedTicket"
        :available-modes="ticketOptionsForSelected"
        :reachable="isSelectedReachable"
        :is-my-turn="store.isMyTurn"
        :submitting="submitting"
        :move-error="moveError"
        :valid-moves="store.validMoves"
        :nodes="nodes"
        :double-mode="doubleMode"
        :has-double-ticket="hasDoubleTicket"
        :mr-x-double-move-pending="mrXDoubleMovePending"
        @select-ticket="selectedTicket = $event"
        @confirm-move="confirmMove"
        @select-node="handleSelectNode"
        @declare-double="doubleMode = true"
        @cancel-double="doubleMode = false"
        @leave="handleLeave"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { ArrowLeft } from 'lucide-vue-next'
import { useGameStore } from '../stores/gameStore'
import { leaveGame, getMap, getGame, getValidMoves, submitMove } from '../api/gameApi'
import type { GraphNode, GraphEdge, DemoPlayer, DemoTicket, ValidMoveDTO } from '../types/game'
import { MODE_COLORS, modeLabel } from '../utils/transportModes'
import GameMap from '../components/game/GameMap.vue'
import InfoPanel from '../components/game/InfoPanel.vue'

const route  = useRoute()
const router = useRouter()
const store  = useGameStore()

const gameId = computed(() => route.params.id as string)

// ── Map data ──────────────────────────────────────────────────────────────────

const nodes    = ref<GraphNode[]>([])
const edges    = ref<GraphEdge[]>([])
const mapError = ref<string | null>(null)

// ── Move state ────────────────────────────────────────────────────────────────

const selectedNode   = ref<GraphNode | null>(null)
const selectedTicket = ref<string | null>(null)
const submitting     = ref(false)
const moveError      = ref<string | null>(null)
const doubleMode     = ref(false)

// ── Derived state ─────────────────────────────────────────────────────────────

const gameState = computed(() => store.gameState)

const myNodeId = computed<number>(() => {
  return store.myPlayer?.nodeId ?? 0
})

const reachableNodeIds = computed<Set<number>>(() => {
  return new Set(store.validMoves.map(m => m.nodeId))
})

const isSelectedReachable = computed(() =>
  !!selectedNode.value && reachableNodeIds.value.has(selectedNode.value.id)
)

const ticketOptionsForSelected = computed<string[]>(() => {
  if (!selectedNode.value) return []
  const move = store.validMoves.find(m => m.nodeId === selectedNode.value!.id)
  return move?.ticketOptions ?? []
})

// Player colors for detectives (up to 5)
const DETECTIVE_COLORS = ['#2563eb', '#16a34a', '#d97706', '#7c3aed', '#db2777']

const displayPlayers = computed<DemoPlayer[]>(() => {
  if (!gameState.value) return []
  let detIdx = 0
  return gameState.value.players.map(p => {
    const isMe = p.id === store.playerId
    if (p.role === 'MR_X') {
      return { name: p.name, isYou: isMe, role: 'MR_X', node: p.nodeId, color: '#ef4444' }
    } else {
      const color = DETECTIVE_COLORS[detIdx++ % DETECTIVE_COLORS.length]
      return { name: p.name, isYou: isMe, role: 'DETECTIVE', node: p.nodeId, color }
    }
  })
})

const mrXDoubleMovePending = computed(() => gameState.value?.mrXDoubleMovePending ?? false)

const hasDoubleTicket = computed(() => {
  if (store.myRole !== 'MR_X') return false
  const count = store.myPlayer?.tickets?.DOUBLE ?? 0
  return count !== 0
})

const myTickets = computed<DemoTicket[]>(() => {
  const t = store.myPlayer?.tickets
  if (!t) return []
  const order: Array<{ type: string; label: string; color: string }> = [
    { type: 'ESCOOTER', label: 'Escooter', color: MODE_COLORS.ESCOOTER },
    { type: 'BUS',      label: 'Bus',      color: MODE_COLORS.BUS },
    { type: 'TRAIN',    label: 'Train',    color: MODE_COLORS.TRAIN },
    { type: 'FERRY',    label: 'Ferry',    color: MODE_COLORS.FERRY },
    { type: 'BLACK',    label: modeLabel('BLACK'), color: MODE_COLORS.BLACK },
    { type: 'DOUBLE',   label: 'Double',   color: '#f59e0b' },
  ]
  return order
    .filter(o => t[o.type as keyof typeof t] !== undefined && t[o.type as keyof typeof t] !== 0)
    .map(o => ({ ...o, count: t[o.type as keyof typeof t] as number }))
})

const turnLabel = computed(() => {
  if (!gameState.value || gameState.value.phase !== 'IN_PROGRESS') return ''
  const cur = gameState.value.players.find(p => p.id === gameState.value!.currentPlayerId)
  if (!cur) return ''
  if (store.isMyTurn) return 'Your Turn'
  return cur.role === 'MR_X' ? "Mr X's Turn" : `${cur.name}'s Turn`
})

const turnBadgeClass = computed(() => ({
  'turn-badge--mrx':       gameState.value?.turnPhase === 'MR_X_TURN',
  'turn-badge--detective': gameState.value?.turnPhase === 'DETECTIVE_TURN',
  'turn-badge--mine':      store.isMyTurn,
}))

// ── WebSocket ─────────────────────────────────────────────────────────────────

let stompClient: Client | null = null

function connectWs() {
  if (!store.playerId || gameId.value === 'preview') return

  stompClient = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    onConnect: () => {
      // Per-player state topic
      stompClient!.subscribe(
        `/topic/games/${gameId.value}/players/${store.playerId}`,
        msg => {
          const state = JSON.parse(msg.body)
          store.updateGameState(state)
          if (state.phase === 'ENDED') {
            stompClient?.deactivate()
            router.push(`/game/${gameId.value}/end`)
          }
        }
      )
      // Valid moves pushed by server when it becomes this player's turn
      stompClient!.subscribe(
        `/topic/games/${gameId.value}/players/${store.playerId}/valid-moves`,
        msg => {
          const data = JSON.parse(msg.body)
          store.setValidMoves(data.moves ?? [])
        }
      )
    },
  })
  stompClient.activate()
}

// ── Lifecycle ─────────────────────────────────────────────────────────────────

onMounted(async () => {
  // Load map
  try {
    const map = await getMap()
    nodes.value = map.nodes
    edges.value = map.edges
  } catch (e) {
    mapError.value = e instanceof Error ? e.message : 'Failed to load map'
  }

  // Fetch current (role-filtered) game state
  if (store.playerId && gameId.value && gameId.value !== 'preview') {
    try {
      const state = await getGame(gameId.value, store.playerId)
      store.updateGameState(state)
      if (state.phase === 'ENDED') {
        router.push(`/game/${gameId.value}/end`)
        return
      }
      // If it's already our turn, fetch valid moves
      if (state.currentPlayerId === store.playerId && state.phase === 'IN_PROGRESS') {
        const moves = await getValidMoves(gameId.value, store.playerId)
        store.setValidMoves(moves.moves)
      }
    } catch { /* use whatever was already in the store */ }
  }

  connectWs()
})

onUnmounted(() => {
  stompClient?.deactivate()
})

// Clear double mode if the turn moves away from us
watch(() => store.isMyTurn, (nowMyTurn) => {
  if (!nowMyTurn) doubleMode.value = false
})

// Also fetch valid moves when the store says it becomes our turn
// (handles cases where the WebSocket push arrived before we subscribed)
watch(
  () => store.isMyTurn,
  async (nowMyTurn) => {
    if (nowMyTurn && store.validMoves.length === 0 && gameId.value && store.playerId) {
      try {
        const moves = await getValidMoves(gameId.value, store.playerId)
        store.setValidMoves(moves.moves)
      } catch { /* ignore */ }
    }
  }
)

// ── Interaction ───────────────────────────────────────────────────────────────

function handleSelectNode(node: GraphNode | null) {
  if (!store.isMyTurn) return
  if (node?.id === myNodeId.value) return
  selectedNode.value = node
  selectedTicket.value = null
  moveError.value = null
}

async function confirmMove() {
  if (!selectedNode.value || !selectedTicket.value || !store.playerId || !gameId.value) return
  const nodeId = selectedNode.value.id
  const ticket = doubleMode.value ? `DOUBLE_${selectedTicket.value}` : selectedTicket.value
  submitting.value = true
  moveError.value = null
  selectedNode.value = null
  selectedTicket.value = null
  doubleMode.value = false
  store.setValidMoves([])
  try {
    await submitMove(gameId.value, store.playerId, nodeId, ticket)
  } catch (e) {
    moveError.value = e instanceof Error ? e.message : 'Move failed'
  } finally {
    submitting.value = false
  }
}

async function handleLeave() {
  stompClient?.deactivate()
  const id  = gameId.value
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
  @apply h-screen bg-gray-50 dark:bg-gray-950 flex flex-col overflow-hidden;
}
.header {
  @apply bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 px-4 py-3 flex items-center justify-between shrink-0;
}
.header-left {
  @apply flex items-center gap-3;
}
.back-btn {
  @apply text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white transition-colors;
}
.game-title  { @apply text-gray-900 dark:text-white font-bold; }
.game-subtitle { @apply text-gray-500 dark:text-gray-600 text-sm font-mono; }
.header-right { @apply flex items-center gap-3; }
.round-label  { @apply text-gray-600 dark:text-gray-400 text-sm; }
.round-num    { @apply text-gray-900 dark:text-white font-mono; }
.turn-badge {
  @apply text-sm px-3 py-1 rounded-full bg-gray-200 dark:bg-gray-800 text-gray-700 dark:text-gray-300 transition-colors;
}
.turn-badge--mrx       { @apply bg-red-600/20 text-red-400; }
.turn-badge--detective { @apply bg-blue-600/20 text-blue-400; }
.turn-badge--mine      { @apply bg-green-600/20 text-green-400 font-semibold; }
.double-badge {
  @apply text-xs px-2 py-1 rounded-full bg-amber-600/20 text-amber-400 font-medium;
}
.role-badge {
  @apply text-xs font-bold px-2.5 py-1 rounded-full border;
}
.role-badge--mrx        { @apply bg-red-950 text-red-400 border-red-800; }
.role-badge--detective  { @apply bg-blue-950 text-blue-400 border-blue-800; }
.body { @apply flex flex-1 overflow-hidden flex-col md:flex-row; }
.map-error {
  @apply bg-red-900/20 border-b border-red-700 text-red-400 text-sm px-4 py-2;
}
</style>
