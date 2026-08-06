<template>
  <div class="code-card">
    <p class="card-hint">Share this code with players</p>
    <div class="code-row">
      <span class="code-text">{{ code || '——————' }}</span>
      <button @click="copyCode" class="copy-btn" title="Copy code">
        <Check v-if="copied" :size="20" class="text-green-500" />
        <ClipboardCopy v-else :size="20" />
      </button>
    </div>

    <!-- Same code as a clickable link — the /:code route pre-fills the join
         form, so pasting this anywhere (chat, text) is a one-click join. -->
    <div v-if="code" class="link-row">
      <span class="link-text">{{ joinLink }}</span>
      <button @click="copyLink" class="copy-btn" title="Copy link">
        <Check v-if="linkCopied" :size="18" class="text-green-500" />
        <ClipboardCopy v-else :size="18" />
      </button>
    </div>

    <!-- Same link again, as a QR code — for a phone in the same room, scanning
         beats typing a 6-char code or fumbling a shared link. Fixed black-on-
         white regardless of app theme, since that's what scanners expect. -->
    <div v-if="qrDataUrl" class="qr-wrap">
      <img :src="qrDataUrl" :alt="`QR code to join game ${code}`" class="qr-code" />
      <p class="qr-hint">Scan to join</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ClipboardCopy, Check } from 'lucide-vue-next'
import QRCode from 'qrcode'

const props = defineProps<{ code: string }>()
const copied = ref(false)
const linkCopied = ref(false)
const qrDataUrl = ref('')

const joinLink = computed(() => props.code ? `${window.location.origin}/${props.code}` : '')

watch(joinLink, async (link) => {
  if (!link) { qrDataUrl.value = ''; return }
  try {
    qrDataUrl.value = await QRCode.toDataURL(link, {
      margin: 1,
      width: 176,
      color: { dark: '#111827', light: '#ffffff' },
    })
  } catch {
    qrDataUrl.value = ''
  }
}, { immediate: true })

async function copyCode() {
  if (!props.code) return
  await navigator.clipboard.writeText(props.code)
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}

async function copyLink() {
  if (!joinLink.value) return
  await navigator.clipboard.writeText(joinLink.value)
  linkCopied.value = true
  setTimeout(() => { linkCopied.value = false }, 2000)
}
</script>

<style scoped>
@reference "tailwindcss";
@variant dark (&:is(.dark *));

.code-card {
  @apply bg-gray-100 dark:bg-gray-900 rounded-lg p-6 space-y-3;
}
.card-hint {
  @apply text-sm text-gray-600 dark:text-gray-400;
}
.code-row {
  @apply flex items-center justify-between gap-3;
}
.code-text {
  @apply text-3xl font-mono font-bold tracking-widest text-gray-900 dark:text-white;
}
.copy-btn {
  @apply text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white transition-colors shrink-0;
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
