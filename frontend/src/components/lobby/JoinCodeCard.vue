<template>
  <div class="card space-y-3">
    <p class="card-hint">Share this code with players</p>
    <div class="code-row">
      <span class="code-text">{{ code || '——————' }}</span>
      <button @click="copy(code, 'code')" class="icon-button shrink-0" title="Copy code">
        <Check v-if="copied === 'code'" :size="20" class="text-green-500" />
        <ClipboardCopy v-else :size="20" />
      </button>
    </div>

    <!-- The same code as a link: the /:code route fills in the join form -->
    <div v-if="code" class="link-row">
      <span class="link-text">{{ joinLink }}</span>
      <button @click="copy(joinLink, 'link')" class="icon-button shrink-0" title="Copy link">
        <Check v-if="copied === 'link'" :size="18" class="text-green-500" />
        <ClipboardCopy v-else :size="18" />
      </button>
    </div>

    <!-- The same link as a QR code, for phones in the same room. Always black
         on white whatever the theme, since that's what scanners expect. -->
    <div v-if="qrImage" class="qr-wrap">
      <img :src="qrImage" :alt="`QR code to join game ${code}`" class="qr-code" />
      <p class="qr-hint">Scan to join</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ClipboardCopy, Check } from 'lucide-vue-next'
import QRCode from 'qrcode'
import { SITE_ADDRESS } from '../../shared/api'

const props = defineProps<{ code: string }>()

// SITE_ADDRESS ends in '/', so this is e.g. https://host/WXYZ12 or https://host/mrx/WXYZ12
const joinLink = computed(() => props.code ? `${window.location.origin}${SITE_ADDRESS}${props.code}` : '')

// Drawn at 176 px but shown at 132 px, so it stays sharp on high-density screens
const qrImage = ref('')
watch(joinLink, async (link) => {
  qrImage.value = link
    ? await QRCode.toDataURL(link, { margin: 1, width: 176, color: { dark: '#111827', light: '#ffffff' } }).catch(() => '')
    : ''
}, { immediate: true })

// Which button shows a tick for 2 s after copying
const copied = ref<'code' | 'link' | null>(null)
async function copy(text: string, which: 'code' | 'link') {
  if (!text) return
  await navigator.clipboard.writeText(text)
  copied.value = which
  setTimeout(() => { copied.value = null }, 2000)
}
</script>

<style scoped>
@reference "../../app/style.css";

.card-hint {
  @apply text-sm text-gray-600 dark:text-gray-400;
}
.code-row {
  @apply flex items-center justify-between gap-3;
}
.code-text {
  @apply text-3xl font-mono font-bold tracking-widest text-gray-900 dark:text-white;
}
.link-row {
  @apply flex items-center justify-between gap-3 pt-2 border-t border-gray-200 dark:border-gray-800;
}
.link-text {
  @apply text-sm font-mono text-gray-500 dark:text-gray-400 truncate;
}
.qr-wrap {
  @apply flex flex-col items-center gap-1.5 pt-3 border-t border-gray-200 dark:border-gray-800;
}
.qr-code {
  @apply rounded-md bg-white p-2 shadow-sm w-[132px] h-[132px];
}
.qr-hint {
  @apply text-xs text-gray-500 dark:text-gray-400;
}
</style>
