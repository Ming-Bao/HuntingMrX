<template>
  <div class="log-section">
    <p class="section-label">Mr X Log</p>
    <div v-if="log.length === 0" class="log-empty">No moves yet</div>
    <div v-else class="log-scroll">
      <div v-for="(entry, i) in log" :key="i" class="log-row">
        <span class="log-round">
          R{{ entry.round }}<span v-if="entry.leg === 2" class="log-leg">b</span>
        </span>
        <span class="log-chips">
          <span v-if="entry.doubleMove" class="mode-chip chip-double">DOUBLE</span>
          <span class="mode-chip" :style="{ backgroundColor: modeColor(entry.ticketUsed) }">
            {{ modeLabel(entry.ticketUsed) }}
          </span>
          <span v-if="entry.nodeId != null" class="node-chip">{{ entry.nodeId }}</span>
        </span>
      </div>
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
/* The revealed node number is the single most important fact in this log —
   it was previously the dimmest text in the whole panel (gray-400/500),
   easy to miss entirely. Recast as a solid chip instead of plain dim text,
   in the same red used for reveal moments elsewhere (the reveal popup, the
   map ring) so "this is where Mr X was spotted" reads as one consistent
   visual language across the app rather than a washed-out afterthought. */
.node-chip {
  @apply text-xs font-mono font-bold text-white
         bg-red-600 dark:bg-red-500
         px-1.5 py-0.5 rounded ml-1;
}
</style>
