<template>
  <div class="info-panel">
    <!-- Players -->
    <div class="panel-section">
      <p class="section-label">Players</p>
      <div v-for="player in players" :key="player.name" class="player-row">
        <div class="player-info">
          <div class="player-dot" :style="{ backgroundColor: player.color }"></div>
          <span class="player-name">{{ player.name }}</span>
          <span v-if="player.isYou" class="player-you">(you)</span>
        </div>
        <span class="player-location">
          {{ player.role === 'MR_X' ? '?' : `Node ${player.node}` }}
        </span>
      </div>
    </div>

    <!-- Tickets -->
    <TicketGrid :tickets="tickets" />

    <!-- Mr X Log -->
    <MrXLog :log="mrXLog" />

    <!-- Reachable nodes (only shown on your turn) -->
    <div v-if="isMyTurn && validMoves.length > 0" class="panel-section reachable-section">
      <p class="section-label">Reachable Nodes</p>
      <div
        v-for="move in validMoves"
        :key="move.nodeId"
        class="reachable-row"
        :class="{ 'reachable-row--selected': selectedNode?.id === move.nodeId }"
        @click="$emit('select-node', nodeById.get(move.nodeId) ?? null)"
      >
        <span class="reachable-label">{{ nodeById.get(move.nodeId)?.label ?? `Node ${move.nodeId}` }}</span>
        <span class="reachable-modes">
          <span
            v-for="mode in uniqueTransports(move.ticketOptions)"
            :key="mode"
            class="mode-chip"
            :style="{ backgroundColor: modeColor(mode) }"
          >{{ modeLabel(mode) }}</span>
        </span>
      </div>
    </div>

    <!-- Double ticket declare / status -->
    <div v-if="isMyTurn && hasDoubleTicket && !mrXDoubleMovePending" class="panel-section double-declare">
      <div v-if="!doubleMode" class="double-idle">
        <button class="double-btn" @click="$emit('declare-double')">Use Double Ticket</button>
      </div>
      <div v-else class="double-active-row">
        <span class="double-active-label">Double Move — Leg 1</span>
        <button class="cancel-btn" @click="$emit('cancel-double')">Cancel</button>
      </div>
    </div>
    <div v-if="isMyTurn && mrXDoubleMovePending" class="panel-section double-leg2">
      Double Move — Leg 2
    </div>

    <!-- Move selector -->
    <MoveSelector
      v-if="isMyTurn"
      :selected-node="selectedNode"
      :available-modes="availableModes"
      :selected-ticket="selectedTicket"
      :reachable="reachable"
      :submitting="submitting"
      :move-error="moveError"
      @select-ticket="$emit('select-ticket', $event)"
      @confirm="$emit('confirm-move')"
    />
    <div v-else-if="isMyTurn === false" class="waiting-msg">
      Waiting for other players…
    </div>

    <!-- Leave -->
    <div class="leave-section">
      <button @click="$emit('leave')" class="leave-btn">Leave Game</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { GraphNode, DemoPlayer, DemoTicket, MrXLogEntry, ValidMoveDTO } from '../../types/game'
import { modeLabel, modeColor } from '../../utils/transportModes'
import TicketGrid from './TicketGrid.vue'
import MrXLog from './MrXLog.vue'
import MoveSelector from './MoveSelector.vue'

const props = defineProps<{
  players: DemoPlayer[]
  tickets: DemoTicket[]
  mrXLog: MrXLogEntry[]
  selectedNode: GraphNode | null
  selectedTicket: string | null
  availableModes: string[]
  reachable: boolean
  isMyTurn: boolean
  submitting: boolean
  moveError: string | null
  validMoves: ValidMoveDTO[]
  nodes: GraphNode[]
  doubleMode: boolean
  hasDoubleTicket: boolean
  mrXDoubleMovePending: boolean
}>()

defineEmits<{
  'select-ticket': [mode: string]
  'confirm-move': []
  'select-node': [node: GraphNode | null]
  'declare-double': []
  'cancel-double': []
  leave: []
}>()

const nodeById = computed(() => new Map(props.nodes.map(n => [n.id, n])))

function uniqueTransports(ticketOptions: string[]): string[] {
  return [...new Set(ticketOptions)]
}
</script>

<style scoped>
@reference "tailwindcss";
@variant dark (&:is(.dark *));

.info-panel {
  @apply w-full h-[42vh] md:w-72 md:h-auto
         bg-gray-100 dark:bg-gray-900
         border-t md:border-t-0 md:border-l border-gray-200 dark:border-gray-800
         flex flex-col overflow-y-auto shrink-0;
}
.panel-section {
  @apply p-4 border-b border-gray-200 dark:border-gray-800;
}
.section-label {
  @apply text-sm text-gray-500 uppercase tracking-wider mb-3;
}
.player-row {
  @apply flex items-center justify-between py-1.5;
}
.player-info {
  @apply flex items-center gap-2;
}
.player-dot {
  @apply w-3 h-3 rounded-full;
}
.player-name {
  @apply text-base text-gray-900 dark:text-white;
}
.player-you {
  @apply text-sm text-gray-500 dark:text-gray-600;
}
.player-location {
  @apply text-sm text-gray-500 font-mono;
}
.waiting-msg {
  @apply px-4 py-3 text-sm text-gray-500 dark:text-gray-600 italic;
}
.double-declare { @apply py-3 px-4; }
.double-idle { @apply flex; }
.double-btn {
  @apply w-full py-2 rounded-lg bg-amber-600 hover:bg-amber-500 text-white text-sm font-semibold transition-colors;
}
.double-active-row {
  @apply flex items-center justify-between;
}
.double-active-label {
  @apply text-sm font-semibold text-amber-400;
}
.cancel-btn {
  @apply text-xs text-gray-400 hover:text-white transition-colors underline;
}
.double-leg2 {
  @apply py-2 px-4 text-sm font-semibold text-amber-400 bg-amber-900/20;
}
.reachable-section {
  @apply max-h-48 overflow-y-auto;
}
.reachable-row {
  @apply flex items-center justify-between py-1.5 px-1 rounded cursor-pointer
         hover:bg-gray-200 dark:hover:bg-gray-800 transition-colors;
}
.reachable-row--selected {
  @apply bg-blue-100 dark:bg-blue-900/30;
}
.reachable-label {
  @apply text-sm text-gray-800 dark:text-gray-200 font-mono;
}
.reachable-modes {
  @apply flex gap-1 flex-wrap justify-end;
}
.mode-chip {
  @apply text-xs text-white font-medium px-1.5 py-0.5 rounded;
}
.leave-section {
  @apply p-4 mt-auto border-t border-gray-200 dark:border-gray-800;
}
.leave-btn {
  @apply w-full bg-gray-200 dark:bg-gray-800 hover:bg-gray-300 dark:hover:bg-gray-700
         text-gray-800 dark:text-white text-base font-medium py-2.5 rounded-lg transition-colors;
}
</style>
