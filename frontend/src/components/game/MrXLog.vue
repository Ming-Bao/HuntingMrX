<template>
  <div class="log-section">
    <p class="section-label">Mr X Log</p>
    <div v-if="log.length === 0" class="log-empty">No moves yet</div>
    <div v-else class="log-scroll">
      <template v-for="(entry, i) in log" :key="i">
        <div class="log-row">
          <span class="log-round">
            Round {{ entry.round }}<span v-if="entry.leg === 2" class="log-leg">b</span>
          </span>
          <span class="log-chips">
            <span v-if="entry.doubleMove" class="mode-chip chip-double">DOUBLE</span>
            <span class="mode-chip" :style="{ backgroundColor: modeColor(entry.ticketUsed) }">
              {{ modeLabel(entry.ticketUsed) }}
            </span>
          </span>
        </div>
        <!-- Reveal gets its own full-width row between rounds rather than a
             chip stuck onto the move row, so "a move happened" and "Mr X was
             spotted" read as two distinct events, not one crowded line. -->
        <div v-if="entry.nodeId != null" class="log-reveal-row">
          Mr X revealed at Node {{ entry.nodeId }}
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { MrXLogEntry } from '../../types/game'
import { modeColor, modeLabel } from '../../utils/transportModes'

defineProps<{ log: MrXLogEntry[] }>()
</script>

<style scoped>
@reference "tailwindcss";
@variant dark (&:is(.dark *));

.log-section {
  @apply p-4 border-b border-gray-200 dark:border-gray-800;
}
.section-label {
  @apply text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-3;
}
.log-empty {
  @apply text-sm text-gray-600 dark:text-gray-400 italic;
}
/* Same always-visible-scrollbar treatment as InfoPanel's reachable-section —
   see the comment there for why `scroll` + explicit thumb styling instead
   of a plain `overflow-y-auto`, and why 24vh instead of a fixed px/rem cap. */
.log-scroll {
  @apply max-h-[24vh] overflow-y-scroll;
  scrollbar-width: thin;
  scrollbar-color: #9ca3af transparent; /* gray-400 */
}
:global(.dark) .log-scroll {
  scrollbar-color: #374151 transparent; /* gray-700 */
}
.log-scroll::-webkit-scrollbar {
  width: 8px;
}
.log-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.log-scroll::-webkit-scrollbar-thumb {
  background-color: #9ca3af; /* gray-400 */
  border-radius: 9999px;
}
:global(.dark) .log-scroll::-webkit-scrollbar-thumb {
  background-color: #374151; /* gray-700 */
}
.log-row {
  @apply flex items-center justify-between py-1.5 px-1;
}
.log-round {
  @apply text-sm text-gray-500 dark:text-gray-400 font-mono;
}
.log-leg {
  @apply text-gray-500 dark:text-gray-400 ml-0.5;
}
.log-chips {
  @apply flex gap-1 flex-wrap justify-end items-center;
}
.mode-chip {
  @apply text-xs text-white font-medium px-1.5 py-0.5 rounded;
}
.chip-double {
  @apply bg-amber-500;
}
/* The reveal is the single most important fact in this log — it was
   previously just a dim inline number, easy to miss entirely. It now gets
   its own full-width banner row between the round entries, in the same red
   used for reveal moments elsewhere (the reveal popup, the map ring) so
   "this is where Mr X was spotted" reads as a distinct event, not a
   washed-out detail tacked onto a move row. */
.log-reveal-row {
  @apply text-xs font-mono font-bold text-white text-center
         bg-red-600 dark:bg-red-500
         px-1.5 py-1 my-1 rounded;
}
</style>
