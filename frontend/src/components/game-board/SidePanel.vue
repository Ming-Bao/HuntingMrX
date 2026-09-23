<template>
  <div class="info-panel">
    <!-- Players. Click a node number to fly the map there; Mr X shows ? while hidden. -->
    <div class="panel-section">
      <p class="section-label">Players</p>
      <div v-for="player in players" :key="player.name" class="player-row">
        <div class="player-info">
          <span class="player-name" :style="{ backgroundColor: player.color }">{{ player.name }}</span>
          <span v-if="player.isYou" class="player-you">(you)</span>
        </div>
        <button
          v-if="player.node != null"
          class="player-location player-location--clickable"
          title="Focus map on this player"
          @click="$emit('show-on-map', player.node)"
        >Node {{ player.node }}</button>
        <span v-else class="player-location">?</span>
      </div>
    </div>

    <TicketGrid
      :using-double-ticket="usingDoubleTicket"
      @use-double-ticket="$emit('use-double-ticket')"
      @cancel-double-ticket="$emit('cancel-double-ticket')"
    />

    <MrXLog />

    <!-- On your turn: every node you can reach. A ticket chip picks the node
         and ticket together; the map's node popup does the same. -->
    <div v-if="currentGame.isMyTurn && currentGame.possibleMoves.length > 0" class="panel-section scroll-list">
      <p class="section-label">Reachable Nodes</p>
      <p class="section-hint">Tap a ticket to move there</p>
      <div
        v-for="move in currentGame.possibleMoves"
        :key="move.nodeId"
        class="reachable-row"
        :class="{ 'reachable-row--selected': selectedNode?.id === move.nodeId }"
        @click="$emit('select-node', nodeLookup.get(move.nodeId) ?? null)"
      >
        <span class="reachable-label">{{ nodeLookup.get(move.nodeId)?.label ?? `Node ${move.nodeId}` }}</span>
        <!-- .stop: a chip click mustn't also count as a row click, which
             would clear the ticket just picked -->
        <span class="reachable-modes" @click.stop>
          <button
            v-for="ticket in move.ticketOptions"
            :key="ticket"
            type="button"
            class="ticket-chip"
            :class="{ 'ticket-chip--selected': isPicked(move.nodeId, ticket) }"
            :style="{ backgroundColor: ticketColor(ticket) }"
            :aria-label="`Move to node ${move.nodeId} by ${ticketName(ticket)}`"
            :aria-pressed="isPicked(move.nodeId, ticket)"
            @click="pickMove(move.nodeId, ticket)"
          >
            <component :is="ticketIcon(ticket)" :size="13" class="ticket-chip-icon" />
            {{ ticketName(ticket) }}
            <Check v-if="isPicked(move.nodeId, ticket)" :size="12" class="shrink-0 -mr-0.5" />
          </button>
        </span>
      </div>
    </div>

    <!-- Confirm the picked move -->
    <div v-if="currentGame.isMyTurn" class="p-4">
      <template v-if="selectedNode">
        <p class="section-label mb-1">Move to</p>
        <p class="node-name">{{ selectedNodeName }}</p>
        <template v-if="canReachSelected">
          <p v-if="!selectedTicket" class="hint mb-2">Tap a transport ticket in Reachable Nodes above to continue</p>
          <p v-if="moveError" class="move-error">{{ moveError }}</p>
          <button
            :disabled="!selectedTicket || sendingMove"
            class="button button-primary w-full py-2.5"
            @click="$emit('confirm-move')"
          >{{ sendingMove ? 'Moving…' : 'Confirm Move' }}</button>
        </template>
        <p v-else class="hint">No direct connection from your position</p>
      </template>
      <template v-else>
        <p class="section-label mb-1">Move</p>
        <p class="hint">Click a node on the map to select a destination</p>
      </template>
    </div>
    <div v-else class="waiting-msg">Waiting for other players…</div>

    <div class="leave-section">
      <button @click="$emit('leave')" class="button button-secondary w-full py-2.5">Leave Game</button>
    </div>
  </div>
</template>

<script setup lang="ts">
// The game board's sidebar. Reads game data from `currentGame`; the move being
// built (node, ticket, double ticket) belongs to GameBoardPage and is passed in.
import { computed } from 'vue'
import { Check } from 'lucide-vue-next'
import { currentGame } from '../../shared/current-game'
import type { MapNode, PlayerMarker } from '../../shared/types'
import { ticketName, ticketColor, ticketIcon } from '../../shared/tickets'
import TicketGrid from './TicketGrid.vue'
import MrXLog from './MrXLog.vue'

const props = defineProps<{
  players: PlayerMarker[]
  nodes: MapNode[]
  selectedNode: MapNode | null
  selectedTicket: string | null
  sendingMove: boolean
  moveError: string | null
  usingDoubleTicket: boolean
}>()

const emit = defineEmits<{
  'select-node': [node: MapNode | null]
  'select-ticket': [ticket: string]
  'confirm-move': []
  'show-on-map': [nodeId: number]
  'use-double-ticket': []
  'cancel-double-ticket': []
  leave: []
}>()

const nodeLookup = computed(() => new Map(props.nodes.map(node => [node.id, node])))

const canReachSelected = computed(() => currentGame.possibleMoves.some(move => move.nodeId === props.selectedNode?.id))

// Node labels are currently just the id ("42"), so only show the label when
// it's a real name, to avoid "42 — Node 42".
const selectedNodeName = computed(() => {
  const node = props.selectedNode
  if (!node) return ''
  return node.label === String(node.id) ? `Node ${node.id}` : `${node.label} — Node ${node.id}`
})

const isPicked = (nodeId: number, ticket: string) =>
  props.selectedNode?.id === nodeId && props.selectedTicket === ticket

function pickMove(nodeId: number, ticket: string) {
  emit('select-node', nodeLookup.value.get(nodeId) ?? null)
  emit('select-ticket', ticket)
}
</script>

<style scoped>
@reference "../../app/style.css";

.info-panel {
  @apply w-full h-[42vh] md:w-72 md:h-auto
         bg-gray-100 dark:bg-gray-900
         border-t md:border-t-0 md:border-l border-gray-200 dark:border-gray-800
         flex flex-col overflow-y-auto shrink-0;
}
.player-row {
  @apply flex items-center justify-between py-1.5;
}
.player-info {
  @apply flex items-center gap-2;
}
.player-name {
  @apply text-sm font-semibold text-white px-2 py-0.5 rounded-full border border-white/20 shadow-sm;
}
.player-you {
  @apply text-sm text-gray-500 dark:text-gray-400;
}
.player-location {
  @apply text-sm text-gray-500 dark:text-gray-400 font-mono;
}
.player-location--clickable {
  @apply cursor-pointer underline decoration-dotted underline-offset-2
         hover:text-gray-900 dark:hover:text-white transition-colors;
}
.section-hint {
  @apply text-xs text-gray-500 dark:text-gray-400 -mt-2 mb-3;
}
/* One card per node, with the label on its own line, so wrapped chips
   don't run into the next node. */
.reachable-row {
  @apply flex flex-col gap-1.5 p-2.5 rounded-lg cursor-pointer
         bg-gray-200/60 dark:bg-gray-800/40 border border-transparent
         hover:bg-gray-200 dark:hover:bg-gray-800 hover:border-gray-300 dark:hover:border-gray-700
         transition-colors;
}
.reachable-row + .reachable-row {
  @apply mt-2;
}
.reachable-row--selected {
  @apply bg-blue-100 dark:bg-blue-900/30 border-blue-400 dark:border-blue-800;
}
.reachable-label {
  @apply text-sm text-gray-800 dark:text-gray-200 font-mono font-semibold;
}
.reachable-modes {
  @apply flex gap-1.5 flex-wrap cursor-default;
}
.ticket-chip--selected {
  @apply border-white ring-2 ring-white/70 ring-offset-1 ring-offset-gray-100 dark:ring-offset-gray-900;
}
.node-name {
  @apply text-gray-900 dark:text-white font-medium text-base mb-3;
}
.hint {
  @apply text-sm text-gray-600 dark:text-gray-400 italic;
}
.move-error {
  @apply text-sm text-red-500 dark:text-red-400 mb-2;
}
.waiting-msg {
  @apply px-4 py-3 text-sm text-gray-500 dark:text-gray-400 italic;
}
.leave-section {
  @apply p-4 mt-auto border-t border-gray-200 dark:border-gray-800;
}
</style>
