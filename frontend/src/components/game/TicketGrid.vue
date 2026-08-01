<template>
  <div class="ticket-section">
    <p class="section-label">Your Tickets</p>
    <div class="ticket-grid">
      <div v-for="ticket in tickets" :key="ticket.type" class="ticket-pill">
        <span class="ticket-name" :style="{ color: ticket.color }">{{ ticket.label }}</span>
        <span class="ticket-count">{{ ticket.count < 0 ? '∞' : ticket.count }}</span>
      </div>
    </div>

    <!-- Double-ticket action lives with the ticket it spends, instead of as
         its own separate sidebar section further down. -->
    <div v-if="isMyTurn && hasDoubleTicket && !mrXDoubleMovePending" class="double-action">
      <button v-if="!doubleMode" class="double-btn" @click="$emit('declare-double')">Use Double Ticket</button>
      <div v-else class="double-active-row">
        <span class="double-active-label">Double Move — Leg 1</span>
        <button class="cancel-btn" @click="$emit('cancel-double')">Cancel</button>
      </div>
    </div>
    <div v-if="isMyTurn && mrXDoubleMovePending" class="double-leg2">
      Double Move — Leg 2
    </div>
  </div>
</template>

<script setup lang="ts">
import type { DemoTicket } from '../../types/game'

defineProps<{
  tickets: DemoTicket[]
  isMyTurn: boolean
  hasDoubleTicket: boolean
  doubleMode: boolean
  mrXDoubleMovePending: boolean
}>()

defineEmits<{
  'declare-double': []
  'cancel-double': []
}>()
</script>

<style scoped>
@reference "tailwindcss";
@variant dark (&:is(.dark *));

.ticket-section {
  @apply p-4 border-b border-gray-200 dark:border-gray-800;
}
.section-label {
  @apply text-sm text-gray-500 uppercase tracking-wider mb-3;
}
.ticket-grid {
  @apply grid grid-cols-2 gap-2;
}
.ticket-pill {
  @apply flex items-center justify-between bg-gray-200 dark:bg-gray-800 rounded-lg px-3 py-2.5;
}
.ticket-name {
  @apply text-sm font-medium;
}
.ticket-count {
  @apply text-gray-900 dark:text-white text-base font-mono font-bold;
}
.double-action {
  @apply mt-2;
}
.double-btn {
  @apply w-full py-2 rounded-lg bg-amber-600 hover:bg-amber-500 text-white text-sm font-semibold transition-colors;
}
.double-active-row {
  @apply flex items-center justify-between;
}
.double-active-label {
  @apply text-sm font-semibold text-amber-500 dark:text-amber-400;
}
.cancel-btn {
  @apply text-xs text-gray-400 hover:text-gray-900 dark:hover:text-white transition-colors underline;
}
.double-leg2 {
  @apply mt-2 py-2 px-3 rounded-lg text-sm font-semibold text-amber-500 dark:text-amber-400 bg-amber-600/10;
}
</style>
