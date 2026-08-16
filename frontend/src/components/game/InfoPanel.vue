<template>
  <div class="info-panel">
    <!-- Players -->
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
          @click="$emit('focus-node', player.node)"
        >Node {{ player.node }}</button>
        <span v-else class="player-location">?</span>
      </div>
    </div>

    <!-- Tickets (double-ticket declare/status lives inside this section) -->
    <TicketGrid
      :tickets="tickets"
      :is-my-turn="isMyTurn"
      :has-double-ticket="hasDoubleTicket"
      :double-mode="doubleMode"
      :mr-x-double-move-pending="mrXDoubleMovePending"
      @declare-double="$emit('declare-double')"
      @cancel-double="$emit('cancel-double')"
    />

    <!-- Mr X Log -->
    <MrXLog :log="mrXLog" />

    <!-- Reachable nodes (only shown on your turn). Tapping the row previews
         that node on the map; tapping a ticket picks the node + that ticket
         together and is the only way to actually set up a move — the "Move
         to" section below just confirms whatever's picked here. -->
    <div v-if="isMyTurn && validMoves.length > 0" class="panel-section reachable-section">
      <p class="section-label">Reachable Nodes</p>
      <p class="section-hint">Tap a ticket to move there</p>
      <div
        v-for="move in validMoves"
        :key="move.nodeId"
        class="reachable-row"
        :class="{ 'reachable-row--selected': selectedNode?.id === move.nodeId }"
        @click="$emit('select-node', nodeById.get(move.nodeId) ?? null)"
      >
        <span class="reachable-label">{{ nodeById.get(move.nodeId)?.label ?? `Node ${move.nodeId}` }}</span>
        <span class="reachable-modes" @click.stop>
          <button
            v-for="mode in uniqueTransports(move.ticketOptions)"
            :key="mode"
            type="button"
            class="mode-chip"
            :class="{ 'mode-chip--selected': selectedNode?.id === move.nodeId && selectedTicket === mode }"
            :style="{ backgroundColor: modeColor(mode) }"
            :aria-label="`Move to node ${move.nodeId} by ${modeLabel(mode)}`"
            :aria-pressed="selectedNode?.id === move.nodeId && selectedTicket === mode"
            @click.stop="selectMove(move.nodeId, mode)"
          >
            <component :is="modeIcon(mode)" :size="13" class="mode-chip-icon" />
            {{ modeLabel(mode) }}
            <Check v-if="selectedNode?.id === move.nodeId && selectedTicket === mode" :size="12" class="mode-chip-check" />
          </button>
        </span>
      </div>
    </div>

    <!-- Move selector -->
    <MoveSelector
      v-if="isMyTurn"
      :selected-node="selectedNode"
      :selected-ticket="selectedTicket"
      :reachable="reachable"
      :submitting="submitting"
      :move-error="moveError"
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
import { Scooter, Bus, TrainFront, Ship, EyeOff, Check, type LucideIcon } from 'lucide-vue-next'
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

const emit = defineEmits<{
  'select-ticket': [mode: string]
  'confirm-move': []
  'select-node': [node: GraphNode | null]
  'focus-node': [nodeId: number]
  'declare-double': []
  'cancel-double': []
  leave: []
}>()

const nodeById = computed(() => new Map(props.nodes.map(n => [n.id, n])))

function uniqueTransports(ticketOptions: string[]): string[] {
  return [...new Set(ticketOptions)]
}

// A glyph per ticket, on top of the color, so a chip reads as "this specific
// mode" at a glance instead of just "a colored label."
const MODE_ICONS: Record<string, LucideIcon> = {
  ESCOOTER: Scooter,
  BUS:      Bus,
  TRAIN:    TrainFront,
  FERRY:    Ship,
  BLACK:    EyeOff,
}
function modeIcon(mode: string): LucideIcon | undefined {
  return MODE_ICONS[mode]
}

// Picking a mode chip sets node + ticket together — the single interaction
// that used to take two (pick node here, then pick ticket again below).
function selectMove(nodeId: number, mode: string) {
  emit('select-node', nodeById.value.get(nodeId) ?? null)
  emit('select-ticket', mode)
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
  @apply text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-3;
}
.player-row {
  @apply flex items-center justify-between py-1.5;
}
.player-info {
  @apply flex items-center gap-2;
}
.player-name {
  @apply text-sm font-semibold text-white px-2 py-0.5 rounded-full
         border border-white/20 shadow-sm;
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
.waiting-msg {
  @apply px-4 py-3 text-sm text-gray-500 dark:text-gray-400 italic;
}
/* overflow-y: scroll (not auto) so the scrollbar/gutter is always reserved,
   not just when content happens to overflow — a list that's sometimes
   scrollable and sometimes not otherwise shifts width each time the
   scrollbar pops in, and gives no visual hint there's more below the fold
   until you've already scrolled past it once. `scroll` alone doesn't force
   the *thumb* to draw on macOS/GNOME's auto-hide overlay scrollbars though,
   so the Firefox/WebKit rules below style a persistently-visible thin
   scrollbar explicitly rather than relying on the OS default.
   max-height is viewport-relative (24vh), not a fixed px/rem value — a
   fixed cap either wastes most of a tall monitor's screen on a handful of
   rows, or still doesn't leave room for the rest of the sidebar (tickets,
   move selector, leave button) on a short one. This section and the Mr X
   Log below it can both be visible on your own turn, so 24vh each leaves
   roughly half the panel for everything else regardless of screen size. */
.reachable-section {
  @apply max-h-[24vh] overflow-y-scroll;
  scrollbar-width: thin;
  scrollbar-color: #9ca3af transparent; /* gray-400 */
}
:global(.dark) .reachable-section {
  scrollbar-color: #374151 transparent; /* gray-700 */
}
.reachable-section::-webkit-scrollbar {
  width: 8px;
}
.reachable-section::-webkit-scrollbar-track {
  background: transparent;
}
.reachable-section::-webkit-scrollbar-thumb {
  background-color: #9ca3af; /* gray-400 */
  border-radius: 9999px;
}
:global(.dark) .reachable-section::-webkit-scrollbar-thumb {
  background-color: #374151; /* gray-700 */
}
.section-hint {
  @apply text-xs text-gray-500 dark:text-gray-400 -mt-2 mb-3;
}
/* Each node gets its own card, not just a row — with 2+ ticket options the
   chips wrap to a second line, and without a boundary per node that read as
   one long jumbled block instead of a list. Label sits on its own line above
   the chips so the wrap never collides with it either. */
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
/* Chips are the actual controls (pick a node's ticket) — raised, pill-shaped
   and animated on hover/press so they read as buttons, not status labels. */
.mode-chip {
  @apply inline-flex items-center gap-1 text-xs text-white font-semibold
         px-2 py-1 rounded-full cursor-pointer select-none
         border border-white/25 shadow-sm shadow-black/30
         hover:-translate-y-0.5 hover:shadow-md hover:border-white/70 hover:brightness-110
         active:translate-y-0 active:brightness-95
         focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white
         transition-all duration-150;
}
.mode-chip--selected {
  @apply border-white ring-2 ring-white/70 ring-offset-1
         ring-offset-gray-100 dark:ring-offset-gray-900;
}
.mode-chip-icon {
  @apply shrink-0 opacity-90;
}
.mode-chip-check {
  @apply shrink-0 -mr-0.5;
}
.leave-section {
  @apply p-4 mt-auto border-t border-gray-200 dark:border-gray-800;
}
.leave-btn {
  @apply w-full bg-gray-200 dark:bg-gray-800 hover:bg-gray-300 dark:hover:bg-gray-700
         text-gray-800 dark:text-white text-base font-medium py-2.5 rounded-lg transition-colors;
}
</style>
