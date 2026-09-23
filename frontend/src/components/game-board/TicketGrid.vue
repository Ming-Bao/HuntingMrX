<template>
  <div class="panel-section">
    <p class="section-label">Your Tickets</p>
    <div class="ticket-grid">
      <div v-for="ticket in tickets" :key="ticket.type" class="ticket-pill">
        <span class="ticket-name" :style="{ color: ticketColor(ticket.type) }">{{ ticketName(ticket.type) }}</span>
        <!-- Unlimited tickets come back as -1 -->
        <span class="ticket-count">{{ ticket.count < 0 ? '∞' : ticket.count }}</span>
      </div>
    </div>

    <!-- Mr X's double move: declared before picking the first leg. During the
         second leg this only says so, since doubles can't be chained. -->
    <template v-if="currentGame.isMyTurn">
      <div v-if="currentGame.info?.mrXDoubleMovePending" class="double-leg2">Double Move — Leg 2</div>
      <div v-else-if="hasDoubleTicket" class="mt-2">
        <button v-if="!usingDoubleTicket" class="double-button" @click="$emit('use-double-ticket')">Use Double Ticket</button>
        <div v-else class="double-active-row">
          <span class="double-active-label">Double Move — Leg 1</span>
          <button class="icon-button text-xs underline" @click="$emit('cancel-double-ticket')">Cancel</button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { currentGame } from '../../shared/current-game'
import { TICKET_ORDER, ticketColor, ticketName } from '../../shared/tickets'
import type { TicketType } from '../../shared/types'

defineProps<{ usingDoubleTicket: boolean }>()
defineEmits<{ 'use-double-ticket': []; 'cancel-double-ticket': [] }>()

// Your tickets in a fixed order, leaving out the ones you have none of
const tickets = computed(() => {
  const owned = currentGame.me?.tickets
  if (!owned) return []
  return TICKET_ORDER
    .map(type => ({ type, count: owned[type as TicketType] ?? 0 }))
    .filter(ticket => ticket.count !== 0)
})

const hasDoubleTicket = computed(() => (currentGame.me?.tickets?.DOUBLE ?? 0) !== 0)
</script>

<style scoped>
@reference "../../app/style.css";

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
.double-button {
  @apply w-full py-2 rounded-lg bg-amber-600 hover:bg-amber-500 text-white text-sm font-semibold transition-colors;
}
.double-active-row {
  @apply flex items-center justify-between;
}
.double-active-label {
  @apply text-sm font-semibold text-amber-500 dark:text-amber-400;
}
.double-leg2 {
  @apply mt-2 py-2 px-3 rounded-lg text-sm font-semibold text-amber-500 dark:text-amber-400 bg-amber-600/10;
}
</style>
