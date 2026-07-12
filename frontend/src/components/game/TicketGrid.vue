<template>
  <div class="ticket-section">
    <p class="section-label">Your Tickets</p>
    <div class="ticket-grid">
      <div v-for="ticket in tickets" :key="ticket.type" class="ticket-pill">
        <span class="ticket-name" :style="{ color: ticket.color }">{{ ticket.label }}</span>
        <span class="ticket-count">{{ ticket.count < 0 ? '∞' : ticket.count }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { DemoTicket } from '../../types/game'
defineProps<{ tickets: DemoTicket[] }>()
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
</style>
