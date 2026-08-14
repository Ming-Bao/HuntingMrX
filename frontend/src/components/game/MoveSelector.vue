<template>
  <div class="move-section">
    <template v-if="selectedNode">
      <p class="section-label">Move to</p>
      <p class="node-name">{{ nodeDisplayName }}</p>

      <template v-if="reachable">
        <!-- Ticket is picked from the mode chips in the Reachable Nodes list
             above, not here — this section just confirms it. -->
        <p v-if="!selectedTicket" class="ticket-hint">Tap a transport ticket in Reachable Nodes above to continue</p>
        <p v-if="moveError" class="move-error-msg">{{ moveError }}</p>
        <button
          :disabled="!selectedTicket || submitting"
          class="confirm-btn"
          @click="$emit('confirm')"
        >{{ submitting ? 'Moving…' : 'Confirm Move' }}</button>
      </template>

      <template v-else>
        <p class="no-connection">No direct connection from your position</p>
      </template>
    </template>

    <template v-else>
      <p class="section-label">Move</p>
      <p class="move-hint">Click a node on the map to select a destination</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { GraphNode } from '../../types/game'

const props = defineProps<{
  selectedNode: GraphNode | null
  selectedTicket: string | null
  reachable: boolean
  submitting: boolean
  moveError: string | null
}>()

defineEmits<{
  confirm: []
}>()

// Node labels are currently just the node's own id as a string (e.g. "42"),
// so showing both the label and "Node 42" repeated the same number twice.
// Only show the label separately once it actually carries a distinct name.
const nodeDisplayName = computed(() => {
  const node = props.selectedNode
  if (!node) return ''
  return node.label === String(node.id) ? `Node ${node.id}` : `${node.label} — Node ${node.id}`
})
</script>

<style scoped>
@reference "tailwindcss";
@variant dark (&:is(.dark *));

.move-section {
  @apply p-4;
}
.section-label {
  @apply text-sm text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-1;
}
.node-name {
  @apply text-gray-900 dark:text-white font-medium text-base mb-3;
}
.ticket-hint {
  @apply text-sm text-gray-500 dark:text-gray-400 italic mb-2;
}
.confirm-btn {
  @apply w-full bg-blue-600 hover:bg-blue-700 disabled:opacity-40 disabled:cursor-not-allowed
         text-white text-base font-medium py-2.5 rounded-lg transition-colors;
}
.move-error-msg {
  @apply text-sm text-red-500 dark:text-red-400 mb-2;
}
.no-connection {
  @apply text-sm text-gray-600 dark:text-gray-400 italic;
}
.move-hint {
  @apply text-sm text-gray-600 dark:text-gray-400 italic;
}
</style>
