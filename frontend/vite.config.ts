import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// Deploying under a URL path prefix (e.g. a university server that only
// gives out https://host/mrx/ rather than a domain of our own) needs the
// built asset paths, router base, REST calls, and WebSocket connection all
// consistently prefixed. BASE_PATH is the one flag that drives all of it —
// see frontend/src/utils/basePath.ts, docker/render-nginx-conf.sh, and the
// backend's server.servlet.context-path (bound to the same env var name).
// Unset (the default) reproduces today's root-path behavior exactly.
//   BASE_PATH=/mrx npm run build
const BASE_PATH = process.env.BASE_PATH || ''
const base = BASE_PATH ? `${BASE_PATH.replace(/\/+$/, '')}/` : '/'

export default defineConfig({
  base,
  plugins: [vue(), tailwindcss()],
  define: {
    global: 'globalThis',
  },
  server: {
    // Keyed off `base` so dev-server requests match whatever prefix the
    // backend is also running under (export the same BASE_PATH before
    // `mvn spring-boot:run` locally to match). No rewrite needed either
    // side — same reasoning as docker/render-nginx-conf.sh.
    proxy: {
      [`${base}api`]: 'http://localhost:8999',
      [`${base}ws`]: { target: 'http://localhost:8999', ws: true },
      [`${base}test-map.json`]: 'http://localhost:8999',
    }
  },
  build: {
    // maplibre-gl's own chunk (~800 kB) is the expected floor for a WebGL
    // map renderer and isn't further splittable — raised past that so the
    // warning still fires if something unrelated balloons unexpectedly,
    // instead of us tuning it out and missing a real regression.
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // maplibre-gl is a large, rarely-changing vendor library — split it
        // into its own chunk so a deploy that only touches our game-view
        // code doesn't force everyone to re-download all of maplibre-gl
        // too (it stays cached under its own content hash across deploys).
        manualChunks: {
          maplibre: ['maplibre-gl'],
        },
      },
    },
  },
})
