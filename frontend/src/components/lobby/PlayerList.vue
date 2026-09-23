<template>
  <div class="card space-y-3">
    <p class="slot-header">Players ({{ players.length }}/{{ maxPlayers }})</p>

    <div v-for="(player, index) in players" :key="player.id" class="player-row">
      <span class="player-name">{{ player.name }}</span>
      <div class="row-right">
        <span v-if="index === 0" class="badge badge-host">Host</span>
        <span v-else class="badge badge-ready">Ready</span>
        <button v-if="canKick && index !== 0" class="kick-button" @click="$emit('kick', player.id)">Kick</button>
      </div>
    </div>

    <div v-for="i in Math.max(0, maxPlayers - players.length)" :key="'empty-' + i" class="empty-slot">
      Waiting for player…
    </div>
  </div>
</template>

<script setup lang="ts">
import type { PlayerInfo } from '../../shared/types'

// players[0] is always the host, and canKick is true only for the host.
// Everyone else shows as Ready: there's no ready-up step.
defineProps<{ players: PlayerInfo[]; maxPlayers: number; canKick: boolean }>()
defineEmits<{ kick: [playerToRemove: string] }>()
</script>

<style scoped>
@reference "../../app/style.css";

.slot-header {
  @apply text-sm text-gray-600 dark:text-gray-400 font-medium;
}
.player-row {
  @apply flex items-center justify-between py-2;
}
.row-right {
  @apply flex items-center gap-2;
}
.player-name {
  @apply text-gray-900 dark:text-white;
}
.badge {
  @apply text-xs px-2 py-0.5 rounded-full;
}
.badge-host  { @apply bg-blue-600/20 text-blue-400; }
.badge-ready { @apply bg-green-600/20 text-green-400; }
.kick-button {
  @apply text-xs text-red-400 hover:text-red-300 border border-red-800 hover:border-red-600
         px-2 py-0.5 rounded transition-colors;
}
.empty-slot {
  @apply border border-dashed border-gray-300 dark:border-gray-700 rounded-lg py-2 px-3 text-gray-400 dark:text-gray-500 text-sm;
}
</style>
