<template>
  <div class="log-section">
    <p class="section-label">Mr X Log</p>
    <div v-if="log.length === 0" class="log-empty">No moves yet</div>
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
  @apply text-sm text-gray-500 uppercase tracking-wider mb-3;
}
.log-empty {
  @apply text-sm text-gray-600 italic;
}
.log-row {
  @apply flex items-center justify-between py-1.5 px-1;
}
.log-round {
  @apply text-sm text-gray-500 font-mono;
}
.log-leg {
  @apply text-gray-400 ml-0.5;
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
.node-chip {
  @apply text-xs text-gray-400 dark:text-gray-500 font-mono ml-1;
}
</style>
