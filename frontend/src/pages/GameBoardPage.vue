<template>
  <div class="board-page">
    <div class="header">
      <div class="header-left">
        <RouterLink to="/" class="icon-button"><ArrowLeft :size="18" /></RouterLink>
        <h1 class="game-title">Hunting Mr. X</h1>
        <span class="game-subtitle">Wellington Edition</span>
        <span v-if="currentGame.myRole" class="role-badge" :class="currentGame.myRole === 'MR_X' ? 'role-badge--mrx' : 'role-badge--detective'">
          {{ currentGame.myRole === 'MR_X' ? 'Mr. X' : 'Detective' }}
        </span>
      </div>
      <div class="header-center">
        <span class="turn-badge" :class="turnBadgeClass">{{ turnLabel }}</span>
        <span v-if="info?.mrXDoubleMovePending" class="double-badge">Double Move — 2nd leg</span>
      </div>
      <div class="header-right">
        <span class="round-label">
          Round <span class="round-num">{{ info?.round ?? 1 }}</span> / 24
        </span>
        <span v-if="nextReveal" class="next-reveal-label">Next reveal: Round {{ nextReveal }}</span>
      </div>
    </div>

    <!-- Blocking announcement popup (role, your turn, Mr X revealed). If
         several arrive together they stack in one card, and one click
         anywhere dismisses them all. -->
    <div v-if="popups.length" class="popup" @click="popups = []">
      <div class="popup-content">
        <div v-for="(popup, i) in popups" :key="i" class="popup-block" :class="{ 'popup-block--stacked': i > 0 }">
          <p class="popup-title" :class="popup.color">{{ popup.title }}</p>
          <p class="popup-hint">{{ popup.hint }}</p>
        </div>
        <p class="popup-footer">Click anywhere to {{ popups.some(popup => popup.isRole) ? 'begin' : 'continue' }}.</p>
      </div>
    </div>

    <div v-if="mapError" class="map-error">{{ mapError }}</div>

    <div class="body">
      <GameMap
        ref="mapPanel"
        :nodes="nodes"
        :edges="edges"
        :players="players"
        :selected-node="selectedNode"
        @select-node="selectNode"
        @select-ticket="selectedTicket = $event"
      />

      <SidePanel
        :players="players"
        :nodes="nodes"
        :selected-node="selectedNode"
        :selected-ticket="selectedTicket"
        :sending-move="sendingMove"
        :move-error="moveError"
        :using-double-ticket="usingDoubleTicket"
        @select-node="selectNode"
        @select-ticket="selectedTicket = $event"
        @confirm-move="confirmMove"
        @show-on-map="mapPanel?.showNode($event)"
        @use-double-ticket="usingDoubleTicket = true"
        @cancel-double-ticket="usingDoubleTicket = false"
        @leave="leave"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
// The game screen. Owns the live connection and the move being built
// (selected node, ticket, double move). Game data itself lives in `currentGame`,
// which the map and sidebar read directly.
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'
import { currentGame, updateGameInfo, leaveGame } from '../shared/current-game'
import { getMap, getGame, getPossibleMoves, submitMove, keepUpToDate } from '../shared/api'
import type { MapNode, MapConnection, PlayerMarker, GameInfo } from '../shared/types'
import GameMap from '../components/game-board/GameMap.vue'
import SidePanel from '../components/game-board/SidePanel.vue'

const route  = useRoute()
const router = useRouter()
const gameId = route.params.id as string
const info = computed(() => currentGame.info)

// ── Map data ─────────────────────────────────────────────────────────────────

const nodes    = ref<MapNode[]>([])
const edges    = ref<MapConnection[]>([])
const mapError = ref<string | null>(null)
// Lets the sidebar ask the map to fly to a node
const mapPanel = ref<InstanceType<typeof GameMap> | null>(null)

// ── The move being built ─────────────────────────────────────────────────────

const selectedNode      = ref<MapNode | null>(null)
const selectedTicket    = ref<string | null>(null)
const usingDoubleTicket = ref(false)   // Mr X has chosen to spend a Double ticket on this move
const sendingMove       = ref(false)
const moveError         = ref<string | null>(null)

// ── Header and player colours ────────────────────────────────────────────────

const DETECTIVE_COLORS = ['#2563eb', '#16a34a', '#d97706', '#7c3aed', '#db2777']
// Near-black, so Mr X's marker can't be mistaken for a transport colour (BUS is red)
const MR_X_COLOR = '#18181b'

// Colours go by join order, which never changes, so each detective keeps
// the same colour all game
const players = computed<PlayerMarker[]>(() => {
  let detectiveIndex = 0
  return (info.value?.players ?? []).map(player => ({
    name: player.name,
    isYou: player.id === currentGame.myPlayerId,
    role: player.role,
    node: player.nodeId,
    color: player.role === 'MR_X' ? MR_X_COLOR : DETECTIVE_COLORS[detectiveIndex++ % DETECTIVE_COLORS.length],
  }))
})

const turnLabel = computed(() => {
  const current = info.value?.players.find(player => player.id === info.value?.currentPlayerId)
  if (info.value?.phase !== 'IN_PROGRESS' || !current) return ''
  if (currentGame.isMyTurn) return 'Your Turn'
  return current.role === 'MR_X' ? "Mr X's Turn" : `${current.name}'s Turn`
})

const turnBadgeClass = computed(() => ({
  'turn-badge--mrx':       info.value?.turnPhase === 'MR_X_TURN',
  'turn-badge--detective': info.value?.turnPhase === 'DETECTIVE_TURN',
  'turn-badge--mine':      currentGame.isMyTurn,
}))

// Copy of the backend's Game.REVEAL_ROUNDS, used only for the "Next reveal" hint.
// The server decides when a reveal actually happens.
const REVEAL_ROUNDS = [2, 8, 13, 18, 24]
const nextReveal = computed(() => REVEAL_ROUNDS.find(round => round > (info.value?.round ?? 1)) ?? null)

// ── Keeping in sync with the server ──────────────────────────────────────────

let stopUpdates = () => {}

// Handles new game info from anywhere: a live message, a refresh, or the
// reply to our own move.
async function onGameUpdate(state: GameInfo) {
  updateGameInfo(state)
  if (state.phase === 'ENDED') {
    stopUpdates()
    router.push(`/game/${gameId}/end`)
    return
  }
  // Possible moves normally arrive on their own live channel. Ask for them if that message was missed.
  if (currentGame.isMyTurn && currentGame.possibleMoves.length === 0 && currentGame.mySecretKey) {
    try { currentGame.possibleMoves = await getPossibleMoves(gameId, currentGame.mySecretKey) } catch { /* next refresh retries */ }
  }
}

async function refresh() {
  if (!currentGame.mySecretKey) return
  try { await onGameUpdate(await getGame(gameId, currentGame.mySecretKey)) } catch { /* keep what we have */ }
}

onMounted(async () => {
  try {
    const map = await getMap()
    nodes.value = map.nodes
    edges.value = map.edges
  } catch (problem) {
    mapError.value = problem instanceof Error ? problem.message : 'Failed to load map'
  }

  // Opened without joining (e.g. a pasted link): there's nothing to listen to
  if (!currentGame.mySecretKey) return
  // Both channels are private: named by our secret key
  const myChannel = `/topic/games/${gameId}/players/${currentGame.mySecretKey}`
  stopUpdates = keepUpToDate({
    [myChannel]: onGameUpdate,
    [`${myChannel}/valid-moves`]: moves => { currentGame.possibleMoves = moves ?? [] },
  }, refresh)
})

onUnmounted(() => stopUpdates())

// ── Popups ───────────────────────────────────────────────────────────────────

const popups = ref<{ title: string; hint: string; color: string; isRole?: boolean }[]>([])

// Role: once, as soon as we know it. On a refresh it's only known after the first fetch.
let roleShown = false
watch(() => currentGame.myRole, role => {
  if (!role || roleShown) return
  roleShown = true
  popups.value.push(role === 'MR_X'
    ? { title: 'You Are Mr X', hint: 'Evade the detectives for 24 rounds.', color: 'text-red-400', isRole: true }
    : { title: 'You Are a Detective', hint: 'Track down Mr X before round 24.', color: 'text-blue-400', isRole: true })
}, { immediate: true })

// Reveal: a new Mr X log entry carrying a nodeId. The log isn't filtered by
// role, so Mr X and the detectives both see it. The first log we see is a
// starting point (page opened or reconnected), not a new event.
let seenLogLength: number | null = null
// Your turn: each time isMyTurn goes from false to true. It stays true
// between the two legs of a double move, so that doesn't fire twice.
let wasMyTurn = false

watch(info, state => {
  if (!state) return
  const log = state.mrXLog ?? []
  if (seenLogLength !== null && log.length > seenLogLength) {
    const revealed = log.slice(seenLogLength).find(entry => entry.nodeId != null)
    if (revealed) popups.value.push(currentGame.myRole === 'MR_X'
      ? { title: 'You’ve Been Revealed', hint: `Detectives can now see Node ${revealed.nodeId}.`, color: 'text-red-400' }
      : { title: 'Mr X Revealed', hint: `Spotted at Node ${revealed.nodeId}.`, color: 'text-red-400' })
  }
  seenLogLength = log.length

  if (currentGame.isMyTurn && !wasMyTurn) popups.value.push({ title: 'Your Turn', hint: 'It’s your move.', color: 'text-green-400' })
  wasMyTurn = currentGame.isMyTurn
}, { immediate: true })

// ── Making a move ────────────────────────────────────────────────────────────

// A double ticket declared but not used doesn't carry over to a later turn
watch(() => currentGame.isMyTurn, myTurn => { if (!myTurn) usingDoubleTicket.value = false })

// Only on your turn, and not your own node (you can't stay where you are)
function selectNode(node: MapNode | null) {
  if (!currentGame.isMyTurn || node?.id === currentGame.me?.nodeId) return
  selectedNode.value = node
  selectedTicket.value = null
  moveError.value = null
}

async function confirmMove() {
  if (!selectedNode.value || !selectedTicket.value || !currentGame.mySecretKey) return
  const nodeId = selectedNode.value.id
  // The first leg of a double move is sent as e.g. DOUBLE_BUS
  const ticket = usingDoubleTicket.value ? `DOUBLE_${selectedTicket.value}` : selectedTicket.value
  // Clear the pick first, so a double-click can't send the same move twice.
  // The reply brings the next set of possible moves.
  sendingMove.value = true
  moveError.value = null
  selectedNode.value = null
  selectedTicket.value = null
  usingDoubleTicket.value = false
  currentGame.possibleMoves = []
  try {
    // Use the reply straight away rather than waiting for our own move to
    // come back as a live message, which a flaky connection might drop.
    await onGameUpdate(await submitMove(gameId, currentGame.mySecretKey, nodeId, ticket))
  } catch (problem) {
    moveError.value = problem instanceof Error ? problem.message : 'Move failed'
  } finally {
    sendingMove.value = false
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

.board-page {
  @apply h-screen bg-gray-50 dark:bg-gray-950 flex flex-col overflow-hidden;
}
/* A three-column grid, not flex justify-between, keeps the turn badge
   centred however wide the left and right groups get. */
.header {
  @apply bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 px-4 py-3
         grid grid-cols-[1fr_auto_1fr] items-center gap-3 shrink-0;
}
.header-left   { @apply flex items-center gap-3 justify-self-start; }
.header-center { @apply flex items-center gap-3 justify-self-center; }
.header-right  { @apply flex items-center gap-3 justify-self-end; }
.game-title    { @apply text-gray-900 dark:text-white font-bold; }
.game-subtitle { @apply text-gray-500 dark:text-gray-600 text-sm font-mono; }
.round-label   { @apply text-gray-600 dark:text-gray-400 text-sm; }
.round-num     { @apply text-gray-900 dark:text-white font-mono; }
.next-reveal-label { @apply text-gray-500 text-xs italic; }
.turn-badge {
  @apply text-sm px-3 py-1 rounded-full bg-gray-200 dark:bg-gray-800 text-gray-700 dark:text-gray-300 transition-colors;
}
.turn-badge--mrx       { @apply bg-red-600/20 text-red-400; }
.turn-badge--detective { @apply bg-blue-600/20 text-blue-400; }
.turn-badge--mine {
  @apply bg-green-600/20 text-green-400 font-semibold;
  animation: turn-pulse 1.4s ease-in-out infinite;
}
@keyframes turn-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.55); }
  50%      { box-shadow: 0 0 0 7px rgba(34, 197, 94, 0); }
}
.double-badge {
  @apply text-xs px-2 py-1 rounded-full bg-amber-600/20 text-amber-400 font-medium;
}
.role-badge {
  @apply text-xs font-bold px-2.5 py-1 rounded-full border;
}
.role-badge--mrx       { @apply bg-red-950 text-red-400 border-red-800; }
.role-badge--detective { @apply bg-blue-950 text-blue-400 border-blue-800; }
.body { @apply flex flex-1 overflow-hidden flex-col md:flex-row; }
.map-error {
  @apply bg-red-900/20 border-b border-red-700 text-red-400 text-sm px-4 py-2;
}

.popup {
  @apply fixed inset-0 z-50 flex items-center justify-center bg-black/55 cursor-pointer;
  animation: popup-fade 0.2s ease-out;
}
.popup-content {
  @apply text-center select-none;
  animation: popup-grow 0.25s ease-out;
}
.popup-title {
  @apply text-5xl font-extrabold tracking-wide;
}
.popup-hint {
  @apply mt-3 text-gray-300 text-sm;
}
/* Popups after the first read as follow-up notes: smaller, below a hairline */
.popup-block--stacked {
  @apply mt-5 pt-5 border-t border-white/15;
}
.popup-block--stacked .popup-title { @apply text-2xl tracking-normal; }
.popup-block--stacked .popup-hint  { @apply mt-1.5; }
.popup-footer {
  @apply mt-6 text-xs uppercase tracking-widest text-white/40;
}
@keyframes popup-fade {
  from { opacity: 0; }
  to   { opacity: 1; }
}
@keyframes popup-grow {
  from { transform: scale(0.9); opacity: 0; }
  to   { transform: scale(1); opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .turn-badge--mine, .popup, .popup-content { animation: none; }
}
</style>
