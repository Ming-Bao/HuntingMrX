import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// BASE_PATH hosts the site in a sub-folder, e.g. `BASE_PATH=/mrx npm run build`
// for https://host/mrx/. The backend reads the same variable, so both agree on
// the address. Vite wants it with exactly one trailing slash ('/mrx/'), hence
// the tidy-up below.
const BASE_PATH = process.env.BASE_PATH || ''
const base = BASE_PATH ? `${BASE_PATH.replace(/\/+$/, '')}/` : '/'

export default defineConfig({
  base,
  plugins: [vue(), tailwindcss()],
  // sockjs-client expects Node's `global`
  define: {
    global: 'globalThis',
  },
  server: {
    // Dev server: forward API and WebSocket requests to the backend on 8999,
    // under the same prefix.
    proxy: {
      [`${base}api`]: 'http://localhost:8999',
      [`${base}ws`]: { target: 'http://localhost:8999', ws: true },
    }
  },
  build: {
    // maplibre-gl alone is about 800 kB and can't be split further. The limit
    // sits just above it so the warning still catches anything else that grows.
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // maplibre-gl gets its own chunk, which browsers keep cached across
        // deploys that only change our code.
        manualChunks: {
          maplibre: ['maplibre-gl'],
        },
      },
    },
  },
})
