<template>
  <div class="panel-section">
    <p class="section-label">Mr X Log</p>
    <div v-if="log.length === 0" class="log-empty">No moves yet</div>
    <div v-else class="scroll-list">
      <template v-for="(entry, i) in log" :key="i">
        <div class="log-row">
          <!-- "Round 5b" is the second half of a double move -->
          <span class="log-round">
            Round {{ entry.round }}<span v-if="entry.leg === 2" class="ml-0.5">b</span>
          </span>
          <span class="log-chips">
            <span v-if="entry.doubleMove" class="log-chip bg-amber-500">DOUBLE</span>
            <span class="log-chip" :style="{ backgroundColor: ticketColor(entry.ticketUsed) }">
              {{ ticketName(entry.ticketUsed) }}
            </span>
          </span>
        </div>
        <!-- A reveal gets its own red row, so "Mr X moved" and "Mr X was
             spotted" read as two separate events -->
        <div v-if="entry.nodeId != null" class="log-reveal-row">
          Mr X revealed at Node {{ entry.nodeId }}
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { currentGame } from '../../shared/current-game'
import { ticketColor, ticketName } from '../../shared/tickets'

const log = computed(() => currentGame.info?.mrXLog ?? [])
</script>

<style scoped>
@reference "../../app/style.css";

.log-empty {
  @apply text-sm text-gray-600 dark:text-gray-400 italic;
}
.log-row {
  @apply flex items-center justify-between py-1.5 px-1;
}
.log-round {
  @apply text-sm text-gray-500 dark:text-gray-400 font-mono;
}
.log-chips {
  @apply flex gap-1 flex-wrap justify-end items-center;
}
.log-chip {
  @apply text-xs text-white font-medium px-1.5 py-0.5 rounded;
}
.log-reveal-row {
  @apply text-xs font-mono font-bold text-white text-center bg-red-600 dark:bg-red-500 px-1.5 py-1 my-1 rounded;
}
</style>
